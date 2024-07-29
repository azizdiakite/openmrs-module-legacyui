/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.web.controller.patient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import ca.uhn.fhir.parser.IParser;

import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Patient.ContactComponent;
import org.hl7.fhir.r4.model.HumanName;
import org.openmrs.Concept;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PatientIdentifierType.LocationBehavior;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.Relationship;
import org.openmrs.RelationshipType;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.translators.PatientTranslator;
import org.openmrs.util.HttpClient;
import org.openmrs.util.LocationUtility;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.validator.PatientIdentifierValidator;
import org.openmrs.validator.PatientValidator;
import org.openmrs.web.WebConstants;
import org.openmrs.web.controller.person.PersonFormController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.WebRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Credentials;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * This controller is used for the "mini"/"new"/"short" patient form. Only key/important attributes
 * for the patient are displayed and allowed to be edited
 * 
 * @see org.openmrs.web.controller.patient.PatientFormController
 */

@Controller
public class ShortPatientFormController {
	
	private static final Log log = LogFactory.getLog(ShortPatientFormController.class);
	
	private static final String SHORT_PATIENT_FORM_URL = "/admin/patients/shortPatientForm.form";
	
	private static final String FIND_PATIENT_PAGE = "findPatient";
	
	private static final String PATIENT_DASHBOARD_URL = "/patientDashboard.form";
	
	@Autowired
	PatientValidator patientValidator;
	
	@Autowired
	PatientTranslator patientTranslator;
	
	@Autowired
	HttpClient httpClient;
	
	@RequestMapping(method = RequestMethod.GET, value = SHORT_PATIENT_FORM_URL)
	public void showForm() {
	}
	
	@ModelAttribute("patientModel")
	public ShortPatientModel getPatientModel(@RequestParam(value = "patientId", required = false) Integer patientId,
	        @RequestParam(value = "fhirPatientId", required = false) String fhirPatientId, ModelMap model, WebRequest request)
	        throws Exception {
		Patient patient;
		if (patientId != null) {
			patient = Context.getPatientService().getPatientOrPromotePerson(patientId);
			if (patient == null) {
				throw new IllegalArgumentException("No patient or person with the given id");
			}
		} else if (fhirPatientId != null) {
			patient = createPatient(fhirPatientId);
		} else {
			// we may have some details to add to a blank patient
			patient = new Patient();
			String name = request.getParameter("addName");
			if (!StringUtils.isBlank(name)) {
				String gender = request.getParameter("addGender");
				String date = request.getParameter("addBirthdate");
				String age = request.getParameter("addAge");
				PersonFormController.getMiniPerson(patient, name, gender, date, age);
			}
		}
		
		// if we have an existing personName, cache the original name so that we
		// can use it to
		// track changes in givenName, middleName, familyName, will also use
		// it to restore the original values
		if (patient.getPersonName() != null && patient.getPersonName().getId() != null) {
			model.addAttribute("personNameCache", PersonName.newInstance(patient.getPersonName()));
		} else {
			model.addAttribute("personNameCache", new PersonName());
		}
		
		// cache a copy of the person address for comparison in case the name is
		// edited
		if (patient.getPersonAddress() != null && patient.getPersonAddress().getId() != null) {
			model.addAttribute("personAddressCache", patient.getPersonAddress().clone());
		} else {
			model.addAttribute("personAddressCache", new PersonAddress());
		}
		
		String propCause = Context.getAdministrationService().getGlobalProperty("concept.causeOfDeath");
		Concept conceptCause = Context.getConceptService().getConcept(propCause);
		String causeOfDeathOther = "";
		if (conceptCause != null && patient.getPatientId() != null) {
			List<Obs> obssDeath = Context.getObsService().getObservationsByPersonAndConcept(patient, conceptCause);
			
			if (obssDeath.size() == 1) {
				Obs obsDeath = obssDeath.iterator().next();
				causeOfDeathOther = obsDeath.getValueText();
				if (causeOfDeathOther == null) {
					log.debug("cod is null, so setting to empty string");
					causeOfDeathOther = "";
				} else {
					log.debug("cod is valid: " + causeOfDeathOther);
				}
			} else {
				log.debug("obssDeath is wrong size: " + obssDeath.size());
			}
		} else {
			log.debug("No concept cause found");
		}
		// end get 'other' cause of death
		model.addAttribute("causeOfDeathOther", causeOfDeathOther);
		
		return new ShortPatientModel(patient);
	}
	
	@ModelAttribute("locations")
	public List<Location> getLocations() {
		return Context.getLocationService().getAllLocations();
	}
	
	@ModelAttribute("defaultLocation")
	public Location getDefaultLocation() {
		return (LocationUtility.getUserDefaultLocation() != null) ? LocationUtility.getUserDefaultLocation()
		        : LocationUtility.getDefaultLocation();
	}
	
	@ModelAttribute("identifierTypes")
	public List<PatientIdentifierType> getIdentifierTypes() {
		final List<PatientIdentifierType> list = Context.getPatientService().getAllPatientIdentifierTypes();
		return list;
	}
	
	@ModelAttribute("identifierLocationUsed")
	public boolean getIdentifierLocationUsed() {
		List<PatientIdentifierType> pits = Context.getPatientService().getAllPatientIdentifierTypes();
		boolean identifierLocationUsed = false;
		for (PatientIdentifierType pit : pits) {
			if (pit.getLocationBehavior() == null || pit.getLocationBehavior() == LocationBehavior.REQUIRED) {
				identifierLocationUsed = true;
			}
		}
		return identifierLocationUsed;
	}
	
	/**
	 * Handles the form submission by validating the form fields and saving it to the DB
	 * 
	 * @param request the webRequest object
	 * @param relationshipsMap
	 * @param patientModel the modelObject containing the patient info collected from the form
	 *            fields
	 * @param result
	 * @return the view to forward to
	 * @should pass if all the form data is valid
	 * @should create a new patient
	 * @should send the user back to the form in case of validation errors
	 * @should void a name and replace it with a new one if it is changed to a unique value
	 * @should void an address and replace it with a new one if it is changed to a unique value
	 * @should add a new name if the person had no names
	 * @should add a new address if the person had none
	 * @should ignore a new address that was added and voided at same time
	 * @should set the cause of death as none a coded concept
	 * @should set the cause of death as a none coded concept
	 * @should void the cause of death obs that is none coded
	 * @should add a new person attribute with a non empty value
	 * @should not add a new person attribute with an empty value
	 * @should void an existing person attribute with an empty value
	 * @should should replace an existing attribute with a new one when edited
	 * @should not void address if it was not changed
	 * @should void address if it was changed
	 */
	@RequestMapping(method = RequestMethod.POST, value = SHORT_PATIENT_FORM_URL)
	public String saveShortPatient(WebRequest request, @ModelAttribute("personNameCache") PersonName personNameCache,
			@ModelAttribute("personAddressCache") PersonAddress personAddressCache,
			@ModelAttribute("relationshipsMap") Map<String, Relationship> relationshipsMap,
			@RequestParam(value = "continueFlag", required = false) String continueFlag,
			@RequestParam(value = "importedPatientId", required = false) String importedPatientId,
			@ModelAttribute("patientModel") ShortPatientModel patientModel, BindingResult result, Model model) {

			String opencrClientTimeOut = Context.getAdministrationService().getGlobalProperty(
					"opencr.opencrClientTimeOut",
					"30");
		OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(Integer.parseInt(opencrClientTimeOut), TimeUnit.SECONDS) // Set timeout for complete call (including connection, read, and write operations) to 30 seconds
                .build();

		String opencrMatchesUrl = Context.getAdministrationService().getGlobalProperty("opencrMatchesUrl",
				"https://localhost:5001/CR/fhir/matches");

		String opencrMatchesCheckFlag = Context.getAdministrationService().getGlobalProperty(
				"legacyui.enableMatchCheck",
				"true");

		String opencMatches = null;

		// Add the data to the Model
		if (!Context.isAuthenticated()) {
			// return "module/legacyui/template/popupMessage";
			return "module/legacyui/admin/patients/shortPatientForm";

		}

		if (Context.isAuthenticated()) {
			// First do form validation so that we can easily bind errors to
			// fields
			new ShortPatientFormValidator().validate(patientModel, result);
			if (result.hasErrors()) {
				return "module/legacyui/admin/patients/shortPatientForm";
			}

			Patient patient = null;
			patient = getPatientFromFormData(patientModel);

			Errors patientErrors = new BindException(patient, "patient");
			patientValidator.validate(patient, patientErrors);
			if (patientErrors.hasErrors()) {
				// bind the errors to the patientModel object by adding them to
				// result since this is not a patient object
				// so that spring doesn't try to look for getters/setters for
				// Patient in ShortPatientModel
				for (ObjectError error : patientErrors.getAllErrors()) {
					result.reject(error.getCode(), error.getArguments(), "Validation errors found");
				}

				return "module/legacyui/admin/patients/shortPatientForm";
			}

			ContactPoint contactPoint = new ContactPoint();

			for (PersonAttribute iterable_element : patient.getActiveAttributes()) {
				if (iterable_element.getAttributeType().getUuid().equals(Context.getAdministrationService()
						.getGlobalProperty("fhir2.personContactPointAttributeTypeUuid"))){
					contactPoint.setId(iterable_element.getUuid());
				contactPoint.setValue(iterable_element.getValue());
				contactPoint.setUse(ContactPoint.ContactPointUse.MOBILE);
				contactPoint.setSystem(ContactPoint.ContactPointSystem.PHONE);
}
			}

			if (opencrMatchesCheckFlag.equals("true")) {
				List<ContactPoint> myList = new ArrayList<>();
				myList.add(contactPoint);
				org.hl7.fhir.r4.model.Patient fhirResource = patientTranslator.toFhirResource(patient);

				fhirResource.setTelecom(myList);

				// Check if the conversion was successful
				if (fhirResource != null) {
					// Now you can safely use the result
					fhirResource.getName().get(0).setUse(HumanName.NameUse.OFFICIAL);
					HumanName name = fhirResource.getName().get(0);
					if (name.hasGiven() && !name.getGiven().isEmpty()) {
						// Trim the first given name
						String firstGivenName = name.getGiven().get(0).getValue();
						name.getGiven().get(0).setValue(firstGivenName.trim());
					}
			
					// Create a FhirContext
					FhirContext fhirContext = FhirContext.forR4();

					// Create a JSON parser
					IParser jsonParser = fhirContext.newJsonParser();

					// Serialize the Patient resource to JSON
					String jsonPayload = jsonParser.encodeResourceToString(fhirResource);

					// Now, jsonPayload contains the JSON representation of the Patient
					System.out.println("JSON Payload:\n" + jsonPayload);
					
							// Create basic authentication credentials
							String username = Context.getAdministrationService().getGlobalProperty("clientregistry.username");
							String password = Context.getAdministrationService().getGlobalProperty("clientregistry.password");
							String credentials = Credentials.basic(username, password);

							Request apiRequest = new Request.Builder()
									.url(opencrMatchesUrl)
									.post(RequestBody.create(MediaType.parse("application/json"), jsonPayload))
									.header("Authorization", credentials) // Add Authorization header
									.build();
							try (Response apiResponse = client.newCall(apiRequest).execute()) {
								if (apiResponse.isSuccessful()) {
									opencMatches = apiResponse.body().string();
									model.addAttribute("opencMatches", opencMatches);
									if (importedPatientId != null && !importedPatientId.isEmpty()) {
										model.addAttribute("importedPatientID", importedPatientId);
									}
								} else {
									model.addAttribute("queryError", "OpenCR patient match issue");

									System.out.println("Error: " + apiResponse.code() + " - " + apiResponse.message());
									return "module/legacyui/admin/patients/shortPatientForm";

								}
							} catch (IOException e) {
								model.addAttribute("queryError", "OpenCR server error");

								e.printStackTrace();
								return "module/legacyui/admin/patients/shortPatientForm";

							}
				} else {
					// Handle the case where conversion to FHIR resource failed
					System.out.println("Error: Conversion to FHIR resource failed");
				}

				if (!continueFlag.equals("continue")) {
					// return "module/legacyui/template/popupMessage";
					return "module/legacyui/admin/patients/shortPatientForm";
				}

			}

			// check if name/address were edited, void them and replace them
			boolean foundChanges = hasPersonNameOrAddressChanged(patient, personNameCache, personAddressCache);

			try {
				patient = Context.getPatientService().savePatient(patient);
				if (importedPatientId != null && !importedPatientId.isEmpty()) {
					IGenericClient fhirClient = Context.getRegisteredComponent("clientRegistryFhirClient", IGenericClient.class);
					org.hl7.fhir.r4.model.Patient fhirPatient = fhirClient.read()
							.resource(org.hl7.fhir.r4.model.Patient.class).withId(importedPatientId).execute();
							String extensionUrl = "imported";
					String extensionValue = "yes";
					// Check if the extension already exists
					boolean extensionExists = false;
					for (org.hl7.fhir.r4.model.Extension ext : fhirPatient.getExtension()) {
						if (extensionUrl.equals(ext.getUrl())) {
							// Update the existing extension value
							ext.setValue(new org.hl7.fhir.r4.model.StringType(extensionValue));
							extensionExists = true;
							break;
						}
					}

					// Add the extension if it doesn’t exist
					if (!extensionExists) {
						org.hl7.fhir.r4.model.Extension newExtension = new org.hl7.fhir.r4.model.Extension();
						newExtension.setUrl(extensionUrl);
						newExtension.setValue(new org.hl7.fhir.r4.model.StringType(extensionValue));
						fhirPatient.addExtension(newExtension);
					}

					// Update the patient resource
					fhirClient.update()
						.resource(fhirPatient)
						.execute();
				}
				request.setAttribute(WebConstants.OPENMRS_MSG_ATTR,
						Context.getMessageSourceService().getMessage("Patient.saved"), WebRequest.SCOPE_SESSION);

				// TODO do we really still need this, besides ensuring that the
				// cause of death is provided?
				// process and save the death info
				saveDeathInfo(patientModel, request);

				if (!patient.getVoided() && relationshipsMap != null) {
					for (Relationship relationship : relationshipsMap.values()) {
						// if the user added a person to this relationship, save
						// it
						if (relationship.getPersonA() != null && relationship.getPersonB() != null) {
							Context.getPersonService().saveRelationship(relationship);
						}
					}
				}
			} catch (APIException e) {
				log.error("Error occurred while attempting to save patient", e);
				request.setAttribute(WebConstants.OPENMRS_ERROR_ATTR,
						Context.getMessageSourceService().getMessage("Patient.save.error"), WebRequest.SCOPE_SESSION);
				// TODO revert the changes and send them back to the form

				// don't send the user back to the form because the created
				// person name/addresses
				// will be recreated over again if the user attempts to resubmit
				if (!foundChanges) {
					return "module/legacyui/admin/patients/shortPatientForm";
				}
			}

			return "redirect:" + PATIENT_DASHBOARD_URL + "?patientId=" + patient.getPatientId();

		}

		return "module/legacyui/findPatient";
	}
	
	private static String parseToken(String responseBody) {
		// Implement your JSON parsing logic here to extract the token from the response
		// For simplicity, assuming the token is always present in the 'token' field
		// Replace this with your actual JSON parsing logic
		// You might want to use a JSON library for this task
		return responseBody.split("\"token\":")[1].split(",")[0].replaceAll("\"", "");
	}
	
	/**
	 * Convenience method that gets the data from the patientModel
	 * 
	 * @param patientModel the modelObject holding the form data
	 * @return the patient object that has been populated with input from the form
	 */
	private Patient getPatientFromFormData(ShortPatientModel patientModel) {
		
		Patient patient = patientModel.getPatient();
		PersonName personName = patientModel.getPersonName();
		if (personName != null) {
			personName.setPreferred(true);
			patient.addName(personName);
		}
		
		PersonAddress personAddress = patientModel.getPersonAddress();
		
		if (personAddress != null) {
			if (personAddress.isVoided() && StringUtils.isBlank(personAddress.getVoidReason())) {
				personAddress.setVoidReason(Context.getMessageSourceService().getMessage("general.default.voidReason"));
			}
			// don't add an address that is being created and at the
			// same time being removed
			else if (!(personAddress.isVoided() && personAddress.getPersonAddressId() == null)) {
				personAddress.setPreferred(true);
				patient.addAddress(personAddress);
			}
		}
		
		// add all the existing identifiers and any new ones.
		if (patientModel.getIdentifiers() != null) {
			for (PatientIdentifier id : patientModel.getIdentifiers()) {
				// skip past the new ones removed from the user interface(may be
				// they were invalid
				// and the user changed their mind about adding them and they
				// removed them)
				if (id.getPatientIdentifierId() == null && id.isVoided()) {
					continue;
				}
				
				patient.addIdentifier(id);
			}
		}
		
		// add the person attributes
		if (patientModel.getPersonAttributes() != null) {
			for (PersonAttribute formAttribute : patientModel.getPersonAttributes()) {
				// skip past new attributes with no values, because the user left them blank
				if (formAttribute.getPersonAttributeId() == null && StringUtils.isBlank(formAttribute.getValue())) {
					continue;
				}
				
				// if the value has been changed for an existing attribute, void it and create a
				// new one
				if (formAttribute.getPersonAttributeId() != null
				        && !OpenmrsUtil.nullSafeEquals(formAttribute.getValue(),
				            patient.getAttribute(formAttribute.getAttributeType()).getValue())) {
					// As per the logic in Person.addAttribute, the old edited attribute will get
					// voided
					// as this new one is getting added
					formAttribute = new PersonAttribute(formAttribute.getAttributeType(), formAttribute.getValue());
					// AOP is failing to set these in unit tests, just set them here for the tests
					// to pass
					formAttribute.setDateCreated(new Date());
					formAttribute.setCreator(Context.getAuthenticatedUser());
				}
				
				patient.addAttribute(formAttribute);
			}
		}
		
		return patient;
	}
	
	/**
	 * Creates a map of string of the form 3b, 3a and the actual person Relationships
	 * 
	 * @param result
	 * @param person the patient/person whose relationships to return
	 * @param request the webRequest Object
	 * @return map of strings matched against actual relationships
	 */
	@ModelAttribute("relationshipsMap")
	private Map<String, Relationship> getRelationshipsMap(
	        @RequestParam(value = "patientId", required = false) Integer patientId, WebRequest request) {
		Map<String, Relationship> relationshipMap = new LinkedHashMap<String, Relationship>();
		
		if (patientId == null) {
			return relationshipMap;
		}
		
		Person person = Context.getPersonService().getPerson(patientId);
		if (person == null) {
			throw new IllegalArgumentException("Patient does not exist: " + patientId);
		}
		
		// Check if relationships must be shown
		String showRelationships = Context.getAdministrationService().getGlobalProperty(
		    OpenmrsConstants.GLOBAL_PROPERTY_NEWPATIENTFORM_SHOW_RELATIONSHIPS, "false");
		
		if ("false".equals(showRelationships)) {
			return relationshipMap;
		}
		
		// gp is in the form "3a, 7b, 4a"
		String relationshipsString = Context.getAdministrationService().getGlobalProperty(
		    OpenmrsConstants.GLOBAL_PROPERTY_NEWPATIENTFORM_RELATIONSHIPS, "");
		relationshipsString = relationshipsString.trim();
		if (relationshipsString.length() > 0) {
			String[] showRelations = relationshipsString.split(",");
			// iterate over strings like "3a"
			for (String showRelation : showRelations) {
				showRelation = showRelation.trim();
				
				boolean aIsToB = true;
				if (showRelation.endsWith("b")) {
					aIsToB = false;
				}
				
				// trim out the trailing a or b char
				String showRelationId = showRelation.replace("a", "");
				showRelationId = showRelationId.replace("b", "");
				
				RelationshipType relationshipType = Context.getPersonService().getRelationshipType(
				    Integer.valueOf(showRelationId));
				
				// flag to know if we need to create a stub relationship
				boolean relationshipFound = false;
				
				if (person.getPersonId() != null) {
					if (aIsToB) {
						List<Relationship> relationships = Context.getPersonService().getRelationships(null, person,
						    relationshipType);
						if (relationships.size() > 0) {
							relationshipMap.put(showRelation, relationships.get(0));
							relationshipFound = true;
						}
					} else {
						List<Relationship> relationships = Context.getPersonService().getRelationships(person, null,
						    relationshipType);
						if (relationships.size() > 0) {
							relationshipMap.put(showRelation, relationships.get(0));
							relationshipFound = true;
						}
					}
				}
				
				// if no relationship was found, create a stub one now
				if (!relationshipFound) {
					Relationship relationshipStub = new Relationship();
					relationshipStub.setRelationshipType(relationshipType);
					if (aIsToB) {
						relationshipStub.setPersonB(person);
					} else {
						relationshipStub.setPersonA(person);
					}
					
					relationshipMap.put(showRelation, relationshipStub);
				}
				
				// check the request to see if a parameter exists in there
				// that matches to the user desired relation. Overwrite
				// any previous data if found
				String submittedPersonId = request.getParameter(showRelation);
				if (submittedPersonId != null && submittedPersonId.length() > 0) {
					Person submittedPerson = Context.getPersonService().getPerson(Integer.valueOf(submittedPersonId));
					if (aIsToB) {
						relationshipMap.get(showRelation).setPersonA(submittedPerson);
					} else {
						relationshipMap.get(showRelation).setPersonB(submittedPerson);
					}
				}
			}
		}
		
		return relationshipMap;
	}
	
	/**
	 * Processes the death information for a deceased patient and save it to the database
	 * 
	 * @param patientModel the modelObject containing the patient info collected from the form
	 *            fields
	 * @param request webRequest object
	 */
	private void saveDeathInfo(ShortPatientModel patientModel, WebRequest request) {
		// update the death reason
		if (patientModel.getPatient().getDead()) {
			log.debug("Patient is dead, so let's make sure there's an Obs for it");
			// need to make sure there is an Obs that represents the
			// patient's cause of death, if applicable
			
			String codProp = Context.getAdministrationService().getGlobalProperty("concept.causeOfDeath");
			Concept causeOfDeath = Context.getConceptService().getConcept(codProp);
			
			if (causeOfDeath != null) {
				List<Obs> obssDeath = Context.getObsService().getObservationsByPersonAndConcept(patientModel.getPatient(),
				    causeOfDeath);
				if (obssDeath != null) {
					if (obssDeath.size() > 1) {
						log.warn("Multiple causes of death (" + obssDeath.size() + ")?  Shouldn't be...");
					} else {
						Obs obsDeath = null;
						if (obssDeath.size() == 1) {
							// already has a cause of death - let's edit
							// it.
							log.debug("Already has a cause of death, so changing it");
							
							obsDeath = obssDeath.iterator().next();
							
						} else {
							// no cause of death obs yet, so let's make
							// one
							log.debug("No cause of death yet, let's create one.");
							
							obsDeath = new Obs();
							obsDeath.setPerson(patientModel.getPatient());
							obsDeath.setConcept(causeOfDeath);
						}
						
						// put the right concept and (maybe) text in this obs
						Concept currCause = patientModel.getPatient().getCauseOfDeath();
						if (currCause == null) {
							// set to NONE
							log.debug("Current cause is null, attempting to set to NONE");
							String noneConcept = Context.getAdministrationService().getGlobalProperty("concept.none");
							currCause = Context.getConceptService().getConcept(noneConcept);
						}
						
						if (currCause != null) {
							log.debug("Current cause is not null, setting to value_coded");
							obsDeath.setValueCoded(currCause);
							obsDeath.setValueCodedName(currCause.getName());
							
							Date dateDeath = patientModel.getPatient().getDeathDate();
							if (dateDeath == null) {
								dateDeath = new Date();
							}
							obsDeath.setObsDatetime(dateDeath);
							
							// check if this is an "other" concept - if
							// so, then we need to add value_text
							String otherConcept = Context.getAdministrationService().getGlobalProperty(
							    "concept.otherNonCoded");
							Concept conceptOther = Context.getConceptService().getConcept(otherConcept);
							if (conceptOther != null) {
								if (conceptOther.equals(currCause)) {
									// seems like this is an other
									// concept - let's try to get the
									// "other" field info
									String otherInfo = request.getParameter("patient.causeOfDeath_other");
									if (otherInfo == null) {
										otherInfo = "";
									}
									log.debug("Setting value_text as " + otherInfo);
									obsDeath.setValueText(otherInfo);
									
								} else {
									log.debug("New concept is NOT the OTHER concept, so setting to blank");
									obsDeath.setValueText("");
								}
							} else {
								log.debug("Don't seem to know about an OTHER concept, so deleting value_text");
								obsDeath.setValueText("");
							}
							
							if (StringUtils.isBlank(obsDeath.getVoidReason())) {
								obsDeath.setVoidReason(Context.getMessageSourceService().getMessage(
								    "general.default.changeReason"));
							}
							Context.getObsService().saveObs(obsDeath, obsDeath.getVoidReason());
						} else {
							log.debug("Current cause is still null - aborting mission");
						}
					}
				}
			} else {
				log.debug("Cause of death is null - should not have gotten here without throwing an error on the form.");
			}
		}
		
	}
	
	/**
	 * Convenience method that checks if the person name or person address have been changed, should
	 * void the old person name/address and create a new one with the changes.
	 * 
	 * @param patient the patient
	 * @param personNameCache the cached copy of the person name
	 * @param personAddressCache the cached copy of the person address
	 * @return true if the personName or personAddress was edited otherwise false
	 */
	private boolean hasPersonNameOrAddressChanged(Patient patient, PersonName personNameCache,
	        PersonAddress personAddressCache) {
		boolean foundChanges = false;
		PersonName personName = patient.getPersonName();
		if (personNameCache.getId() != null) {
			// if the existing persoName has been edited
			if (!getPersonNameString(personName).equalsIgnoreCase(getPersonNameString(personNameCache))) {
				if (log.isDebugEnabled()) {
					log.debug("Voiding person name with id: " + personName.getId() + " and replacing it with a new one: "
					        + personName.getFullName());
				}
				foundChanges = true;
				// create a new one and copy the changes to it
				PersonName newName = PersonName.newInstance(personName);
				newName.setPersonNameId(null);
				newName.setUuid(null);
				newName.setChangedBy(null);// just in case it had a value
				newName.setDateChanged(null);
				newName.setCreator(Context.getAuthenticatedUser());
				newName.setDateCreated(new Date());
				
				// restore the given,middle and familyName, then void the old
				// name
				personName.setGivenName(personNameCache.getGivenName());
				personName.setMiddleName(personNameCache.getMiddleName());
				personName.setFamilyName(personNameCache.getFamilyName());
				personName.setPreferred(false);
				personName.setVoided(true);
				personName.setVoidReason(Context.getMessageSourceService().getMessage("general.voidReasonWithArgument",
				    new Object[] { newName.getFullName() }, "Voided because it was edited to: " + newName.getFullName(),
				    Context.getLocale()));
				
				// add the created name
				patient.addName(newName);
			}
		}
		
		PersonAddress personAddress = patient.getPersonAddress();
		if (personAddress != null) {
			if (personAddressCache.getId() != null) {
				// if the existing personAddress has been edited
				if (!personAddress.isBlank() && !personAddressCache.isBlank()
				        && !personAddress.equalsContent(personAddressCache)) {
					if (log.isDebugEnabled()) {
						log.debug("Voiding person address with id: " + personAddress.getId()
						        + " and replacing it with a new one: " + personAddress.toString());
					}
					
					foundChanges = true;
					// create a new one and copy the changes to it
					PersonAddress newAddress = (PersonAddress) personAddress.clone();
					newAddress.setPersonAddressId(null);
					newAddress.setUuid(null);
					newAddress.setChangedBy(null);// just in case it had a value
					newAddress.setDateChanged(null);
					newAddress.setCreator(Context.getAuthenticatedUser());
					newAddress.setDateCreated(new Date());
					
					// restore address fields that are checked for changes and
					// void the address
					personAddress.setAddress1(personAddressCache.getAddress1());
					personAddress.setAddress2(personAddressCache.getAddress2());
					personAddress.setAddress3(personAddressCache.getAddress3());
					personAddress.setCityVillage(personAddressCache.getCityVillage());
					personAddress.setCountry(personAddressCache.getCountry());
					personAddress.setCountyDistrict(personAddressCache.getCountyDistrict());
					personAddress.setStateProvince(personAddressCache.getStateProvince());
					personAddress.setPostalCode(personAddressCache.getPostalCode());
					personAddress.setLatitude(personAddressCache.getLatitude());
					personAddress.setLongitude(personAddressCache.getLongitude());
					personAddress.setPreferred(false);
					
					personAddress.setVoided(true);
					personAddress.setVoidReason(Context.getMessageSourceService().getMessage(
					    "general.voidReasonWithArgument", new Object[] { newAddress.toString() },
					    "Voided because it was edited to: " + newAddress.toString(), Context.getLocale()));
					
					// Add the created one
					patient.addAddress(newAddress);
				}
			}
		}
		
		return foundChanges;
	}
	
	/**
	 * Convenience method that transforms a person name to a string while ignoring null and blank
	 * values, the returned string only contains the givenName, middleName and familyName
	 * 
	 * @param name the person name to transform
	 * @return the transformed string ignoring blanks and nulls
	 */
	public static String getPersonNameString(PersonName name) {
		List<String> tempName = new ArrayList<String>();
		if (StringUtils.isNotBlank(name.getGivenName())) {
			tempName.add(name.getGivenName().trim());
		}
		if (StringUtils.isNotBlank(name.getMiddleName())) {
			tempName.add(name.getMiddleName().trim());
		}
		if (StringUtils.isNotBlank(name.getFamilyName())) {
			tempName.add(name.getFamilyName().trim());
		}
		
		return StringUtils.join(tempName, " ");
	}
	
	public static String extractUUID(String url) {
		// Regular expression pattern for UUID
		Pattern pattern = Pattern.compile(".*/([a-fA-F0-9\\-]+)$");
		Matcher matcher = pattern.matcher(url);
		
		if (matcher.find()) {
			return matcher.group(1);
		} else {
			// UUID not found
			return null;
		}
	}
	
	public Patient createPatient(String CRIdentifier) throws Exception {

		// Get patient
		// IGenericClient client = new FhirLegacyUIConfig().getFhirClient();
		IGenericClient client = Context.getRegisteredComponent("clientRegistryFhirClient", IGenericClient.class);

		// Patient patient = client.read()
		org.hl7.fhir.r4.model.Patient fhirPatient = client.read()

				.resource(org.hl7.fhir.r4.model.Patient.class).withId(CRIdentifier).execute();

		User user = Context.getAuthenticatedUser();
		Patient p = new Patient();
		p.setPersonCreator(user);
		p.setPersonDateCreated(new Date());
		p.setPersonChangedBy(user);
		p.setPersonDateChanged(new Date());
		p.setUuid(CRIdentifier);

		List<Reference> links = fhirPatient.getLink()
				.stream()
				.map(patientLink -> patientLink.getOther())
				.collect(Collectors.toList());
		String telecom = "";

		if (fhirPatient.getTelecomFirstRep() != null && fhirPatient.getTelecomFirstRep().getValue() != null) {
			telecom = fhirPatient.getTelecomFirstRep().getValue();
		}

		String phoneUUID = Context.getAdministrationService().getGlobalProperty("fhir2.personContactPointAttributeTypeUuid");
		PersonAttributeType type = Context.getPersonService()
				.getPersonAttributeTypeByUuid(phoneUUID);
		PersonAttribute attribute = new PersonAttribute(type, telecom);
		p.addAttribute(attribute);

		// Set patient name
		PersonName name = new PersonName();
		List<org.hl7.fhir.r4.model.StringType> givenNames = fhirPatient.getNameFirstRep().getGiven();
		if (!givenNames.isEmpty()) {

			StringBuilder sb = new StringBuilder();
			for (int i = 1; i < givenNames.size(); i++) {
				sb.append(givenNames.get(i).getValue()).append(" ");
			}

			if (sb.length() > 0) {
				sb.deleteCharAt(sb.length() - 1);
			}

			name = new PersonName(givenNames.get(0).getValue(), sb.toString(),
					fhirPatient.getNameFirstRep().getFamily());

		}

		switch (fhirPatient.getBirthDateElement().getPrecision()) {
			case DAY:
				p.setBirthdateEstimated(false);
				break;
			case MONTH:
			case YEAR:
				p.setBirthdateEstimated(true);
				break;
		}

		// Set patient gender
		if (fhirPatient.hasGender()) {
			switch (fhirPatient.getGender()) {
				case MALE:
					p.setGender("M");
					break;
				case FEMALE:
					p.setGender("F");
					break;
				case OTHER:
					p.setGender("O");
					break;
				case UNKNOWN:
					p.setGender("U");
					break;
			}
		}

		name.setCreator(user);
		name.setDateCreated(new Date());
		name.setChangedBy(user);
		name.setDateChanged(new Date());
		p.addName(name);
		p.setBirthdate(fhirPatient.getBirthDate());
		// Get the identifiers of the patient
		List<org.hl7.fhir.r4.model.Identifier> identifiers = fhirPatient.getIdentifier();
		String fhirIds = Context.getAdministrationService().getGlobalProperty("opencrIdsMapping");
		Map<String, String> optionsMap = new HashMap<>();
		String[] options = fhirIds.split(",");

		// String[] options = fhirIds.split("\\|");
		for (String option : options) {
			String[] keyValue = option.split("\\|");

			if (keyValue.length == 2) {
				String key = keyValue[0].trim();
				String value = keyValue[1].trim();
				optionsMap.put(key, value);
			}
		}
		for (org.hl7.fhir.r4.model.Identifier fhirIdentifier : identifiers) {

			if (fhirIdentifier.getSystem() != null && fhirIdentifier.getValue() != null && optionsMap.get(fhirIdentifier.getSystem()) != null) {

				PatientIdentifier pi = new PatientIdentifier();
				pi.setIdentifier(fhirIdentifier.getValue());

				pi.setIdentifierType(Context.getPatientService().getPatientIdentifierTypeByUuid(
						optionsMap.get(fhirIdentifier.getSystem())));

				pi.setLocation(Context.getLocationService().getDefaultLocation());

				switch (fhirIdentifier.getUse()) {
					case OFFICIAL:
						pi.setPreferred(true);
						break;
					default:
						pi.setPreferred(false);
						break;
				}

				BindException piErrors = new BindException(pi, "patientIdentifier");
				new PatientIdentifierValidator().validate(pi, piErrors);
				if (piErrors.hasErrors()) {
					log.warn(piErrors.getMessage());

				}
				p.addIdentifier(pi);
			}

		}
		/*
		 * if(CRUID != null){
		 * PatientIdentifier pi = new PatientIdentifier();
		 * pi.setIdentifier(CRUID);
		 * pi.setIdentifierType(Context.getPatientService().
		 * getPatientIdentifierTypeByUuid("43a6e699-c2b8-4d5f-9e7f-cf19448d59b7"));
		 * pi.setLocation(Context.getLocationService().getDefaultLocation());
		 * pi.setPreferred(false);
		 * 
		 * p.addIdentifier(pi);
		 * }
		 */
		// Patient patient = Context.getPatientService().savePatient(p);
		// resultsMap.put("success", patient.getPatientId());

		return p;

	}
}
