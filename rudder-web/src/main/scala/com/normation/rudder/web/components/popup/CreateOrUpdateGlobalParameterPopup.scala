/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/
package com.normation.rudder.web.components.popup

import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util.Helpers._

import scala.xml._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import com.normation.rudder.domain.parameters._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.model._
import java.util.regex.Pattern

import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.GlobalParamModAction
import com.normation.rudder.services.workflows.WorkflowService

class CreateOrUpdateGlobalParameterPopup(
    change            : GlobalParamChangeRequest
  , workflowService   : WorkflowService
  , onSuccessCallback : (Either[GlobalParameter, ChangeRequestId]) => JsCmd = { x => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet  with Loggable {

  private[this] val userPropertyService  = RudderConfig.userPropertyService
  private[this] val changeRequestService = RudderConfig.changeRequestService

  def dispatch = {
    case "popupContent" =>  { _ =>   popupContent }
  }

  /* Text variation for
   * - Global Parameter
   * - Create, delete, modify (save)
   */
  private def titles = change.action match {
    case GlobalParamModAction.Delete => "Delete a Global Parameter"
    case GlobalParamModAction.Update => "Update a Global Parameter"
    case GlobalParamModAction.Create => "Add a Global Parameter"
  }

  private[this] val workflowEnabled = workflowService.needExternalValidation()
  private[this] val titleWorkflow = workflowEnabled match {
      case true =>
            <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Request</h4>
              <hr class="css-fix"/>
              <div class="text-center alert alert-info">
                <span class="glyphicon glyphicon-info-sign"></span>
                Workflows are enabled, your change has to be validated in a Change request
              </div>
      case false =>  NodeSeq.Empty
    }

  private[this] def globalParamDiffFromAction(newParameter : GlobalParameter): Box[ChangeRequestGlobalParameterDiff] = {
    change.previousGlobalParam match {
      case None =>
        if ((change.action == GlobalParamModAction.Update) || (change.action == GlobalParamModAction.Create))
          Full(AddGlobalParameterDiff(newParameter))
        else
          Failure(s"Action ${change.action.name} is not possible on a new Global Parameter")
      case Some(d) =>
        change.action match {
          case GlobalParamModAction.Delete                               => Full(DeleteGlobalParameterDiff(d))
          case GlobalParamModAction.Update | GlobalParamModAction.Create => Full(ModifyToGlobalParameterDiff(newParameter))
        }
    }
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else {
      val newParameter = new GlobalParameter(
        name        = ParameterName(parameterName.get),
        value       = parameterValue.get,
        description = parameterDescription.get,
        overridable = parameterOverridable
      )
      val savedChangeRequest = {
        for {
          diff  <- globalParamDiffFromAction(newParameter)
          cr    <- changeRequestService.createChangeRequestFromGlobalParameter(
                         changeRequestName.get
                       , paramReasons.map( _.get ).getOrElse("")
                       , newParameter
                       , change.previousGlobalParam
                       , diff
                       , CurrentUser.actor
                       , paramReasons.map( _.get )
                       )
          wfStarted <- workflowService.startWorkflow(cr.id, CurrentUser.actor, paramReasons.map(_.get))
        } yield {
          cr.id
        }
      }
      savedChangeRequest match {
        case Full(cr) =>
            if (workflowEnabled) {
              // TODO : do more than that
              closePopup() & onSuccessCallback(Right(cr))
            } else
             closePopup() & onSuccessCallback(Left(newParameter))
        case eb:EmptyBox =>
            logger.error("An error occurred while updating the parameter")
            formTracker.addFormError(error("An error occurred while updating the parameter"))
            onFailure
      }
    }
  }

  private[this] def onFailure: JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide()
  }

  private[this] def closePopup() : JsCmd = {
    JsRaw("""$('#createGlobalParameterPopup').bsModal('hide');""")
  }

  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(CreateOrUpdateGlobalParameterPopup.htmlId_popupContainer, popupContent())
  }

  private[this] def updateAndDisplayNotifications(formTracker : FormTracker) : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul>
      </div>
    }
  }

  ////////////////////////// fields for form ////////////////////////
  private[this] val patternName = Pattern.compile("[a-zA-Z0-9_]+");

  private[this] val parameterName = new WBTextField("Name", change.previousGlobalParam.map(_.name.value).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = (change.previousGlobalParam match {
      case Some(entry) => super.inputField % ("disabled" -> "true")
      case None => super.inputField
    }) % ("onkeydown" -> "return processKey(event , 'createParameterSaveButton')")  % ("tabindex" -> "1")
    override def validations =
      valMinLen(1, "The name must not be empty") _ ::
      valRegex(patternName, "The name can contain only letters, digits and underscore") _ :: Nil
  }

  // The value may be empty
  private[this] val parameterValue = new WBTextAreaField("Value", change.previousGlobalParam.map(_.value).getOrElse("")) {
    override def setFilter = trim _ :: Nil
    override def inputField = ( change.action match {
      case GlobalParamModAction.Delete => super.inputField % ("disabled" -> "true")
      case _                           => super.inputField
    }) % ("style" -> "height:4em")  % ("tabindex" -> "2")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations = Nil
  }

  private[this] val parameterDescription = new WBTextAreaField("Description", change.previousGlobalParam.map(_.description).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField =( change.action match {
      case GlobalParamModAction.Delete => super.inputField % ("disabled" -> "true")
      case _ => super.inputField
    })  % ("tabindex" -> "3")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations = Nil
  }


  private[this] def defaultClassName = change.action match {
    case GlobalParamModAction.Update => "btn-success"
    case GlobalParamModAction.Create => "btn-success"
    case GlobalParamModAction.Delete => "btn-danger"
  }

  private[this] val defaultRequestName = s"${change.action.name.capitalize} Global Parameter " + change.previousGlobalParam.map(_.name.value).getOrElse("")
  private[this] val changeRequestName = new WBTextField("Change request title", defaultRequestName) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField % ("onkeydown" -> "return processKey(event , 'createDirectiveSaveButton')") % ("tabindex" -> "4")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  val parameterOverridable = true

  private[this] val paramReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %
        ("style" -> "height:5em;")  % ("tabindex" -> "5") % ("placeholder" -> {userPropertyService.reasonsFieldExplanation})
      override def errorClassName = "col-lg-12 errors-container"
      override def validations = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val formTracker = {
    val fields = parameterName :: parameterValue :: paramReasons.toList ::: {
      if (workflowEnabled) changeRequestName :: Nil
      else Nil
    }
    new FormTracker(fields)
  }

  private[this] def error(msg:String) = Text(msg)

  def popupContent() = {
    val (buttonName, classForButton) = workflowEnabled match {
      case true =>
         ("Open Request", "wideButton btn-primary")
      case false => (change.action.name.capitalize, defaultClassName)
    }

    (
      "#title *" #> titles &
      ".name" #> parameterName.toForm_! &
      ".value" #> parameterValue.toForm_! &
      ".description *" #> parameterDescription.toForm_! &
      "#titleWorkflow *" #> titleWorkflow &
      "#changeRequestName" #> {
          if (workflowEnabled) {
            changeRequestName.toForm
          } else
            Full(NodeSeq.Empty)
      } &
      "#delete *" #> {
        if (change.action == GlobalParamModAction.Delete) {
        <div class="row">
        </div>
        } else {
          NodeSeq.Empty
        }
      } &
      ".itemReason *" #> {
        //if (buttonName=="Delete")
        paramReasons.map { f =>
        <div>
          {if (!workflowEnabled) {
            <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
          }}
            {f.toForm_!}
        </div>
      } } &

      "#cancel"  #> (SHtml.ajaxButton("Cancel", { () => closePopup() })  % ("tabindex" -> "6") % ("class" -> "btn btn-default") ) &
      "#save" #> (SHtml.ajaxSubmit( buttonName, onSubmit _) % ("id" -> "createParameterSaveButton")  % ("tabindex" -> "5") % ("class" -> s"btn ${classForButton}")) andThen
      ".notifications *"  #> { updateAndDisplayNotifications(formTracker) }
    ).apply(formXml())

  }
  private[this] def formXml() : NodeSeq = {
    SHtml.ajaxForm(
    <div id="paramForm" class="modal-backdrop fade in" style="height: 100%;"></div>
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <div class="close" data-dismiss="modal">
                <span aria-hidden="true">&times;</span>
                <span class="sr-only">Close</span>
                </div>
                <h4 class="modal-title" id="title">Here come title</h4>
            </div>
            <div class="modal-body">
                <div class="notifications">Here comes validation messages</div>
                <div class="name"/>
                <div class="value"/>
                <div class="description"/>
                <div id="changeRequestZone">
                    <div id="titleWorkflow"/>
                    <input type="text" id="changeRequestName" />
                </div>
                <div class="itemReason"/>
                <div id="delete" />
            </div>
            <div class="modal-footer">
                <div id="cancel"/>
                <div id="save"/>
            </div>
        </div><!-- /.modal-content -->
    </div><!-- /.modal-dialog -->
    )
  }
}

object CreateOrUpdateGlobalParameterPopup {
  val htmlId_popupContainer = "createGlobalParameterContainer"
  val htmlId_popup = "createGlobalParameterPopup"
}
