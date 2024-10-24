/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.legacyui;

import javax.jms.MapMessage;
import javax.jms.Message;

import org.openmrs.event.EventListener;
import org.openmrs.module.DaemonToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.text.SimpleDateFormat;

@Component
public class EncounterEventListener implements EventListener {
	
	protected final Log log = LogFactory.getLog(getClass());
	
	private static final int EXIT_PROGRAM_ENCOUNTER_TYPE_ID = 5;
	
	private static final String TRANSFERT_FORM_CONCEPT_ID = "160036AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String SELFSTOPPED_FORM_CONCEPT_ID = "165067AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String DECEASED_FORM_CONCEPT_ID = "159AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String NEGATIF_FORM_CONCEPT_ID = "163511AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String TRANSFERED_CONCEPT_VALUE_ID = "165284";
	
	private static final String STOP_CONCEPT_VALUE_ID = "165283";
	
	private static final String DEATH_CONCEPT_VALUE_ID = "165285";
	
	private static final String NEGATIF_CONCEPT_VALUE_ID = "664";
	
	private static final String TRANSFERT_DATE_CONCEPT_UUID = "164595AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String STOP_DATE_CONCEPT_UUID = "165068AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String DEATH_DATE_CONCEPT_UUID = "165233AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private DaemonToken daemonToken;
	
	@Autowired
	DbSessionFactory sessionFactory;
	
	private DbSession getSession() {
		return sessionFactory.getCurrentSession();
	}
	
	public EncounterEventListener(DaemonToken token) {
		daemonToken = token;
	}
	
	public EncounterEventListener() {
	}
	
	public DaemonToken getDaemonToken() {
		return daemonToken;
	}
	
	public void setDaemonToken(DaemonToken daemonToken) {
		this.daemonToken = daemonToken;
	}
	
	@Override
	public void onMessage(Message message) {
		log.trace(String.format("Received message: \n%s", message));
		System.out.println("IN EVENT LISTENER ::: EncounterEventListener");
		try {
			Daemon.runInDaemonThread(() -> {
				try {
					processMessage(message);
				}
				catch (Exception e) {
					log.error(String.format("Failed to process obs message!\n%s", message.toString()), e);
				}
			}, daemonToken);
		} catch (Exception e) {
			log.error(String.format("Failed to start Daemon thread to process message!\n%s", message.toString()), e);
		}

	}
	
	private void processMessage(Message message) throws Exception {
		
		MapMessage mapMessage = (MapMessage) message;
		
		String uuid = mapMessage.getString("uuid");
		
		Encounter encounter = Context.getEncounterService().getEncounterByUuid(uuid);
		
		String opencrMatchesCheckFlag = Context.getAdministrationService().getGlobalProperty("legacyui.enableMatchCheck",
		    "true");
		
		// EXIT PATIENT ENCOUTER 
		if (encounter.getEncounterType().getEncounterTypeId().compareTo(EXIT_PROGRAM_ENCOUNTER_TYPE_ID) == 0
		        && opencrMatchesCheckFlag.equals("true")) {
			
			Patient patient = encounter.getPatient();
			Set<Obs> obsSet = encounter.getObs();
			
			for (Obs obs : encounter.getObs()) {
				
				// TRANSFER
				if (obs.getConcept().getUuid().compareTo(TRANSFERT_FORM_CONCEPT_ID) == 0) {
					
					Date tranfertDate = getObsRelatedDate(obsSet, TRANSFERT_DATE_CONCEPT_UUID);
					updatePatientAttributes(patient, TRANSFERED_CONCEPT_VALUE_ID, tranfertDate);
					break;
				}
				
				// VOLUNTARY STOP
				if (obs.getConcept().getUuid().compareTo(SELFSTOPPED_FORM_CONCEPT_ID) == 0) {
					
					Date stopDate = getObsRelatedDate(obsSet, STOP_DATE_CONCEPT_UUID);
					updatePatientAttributes(patient, STOP_CONCEPT_VALUE_ID, stopDate);
					break;
				}
				
				// DEATH
				if (obs.getConcept().getUuid().compareTo(DECEASED_FORM_CONCEPT_ID) == 0) {
					
					Date deathDate = getObsRelatedDate(obsSet, DEATH_DATE_CONCEPT_UUID);
					updatePatientAttributes(patient, DEATH_CONCEPT_VALUE_ID, deathDate);
					break;
				}
				
				// NEGATIF
				if (obs.getConcept().getUuid().compareTo(NEGATIF_FORM_CONCEPT_ID) == 0) {
					
					updatePatientAttributes(patient, NEGATIF_CONCEPT_VALUE_ID, obs.getValueDatetime());
					break;
				}
			}
			patient = Context.getPatientService().savePatient(patient);
			
		}
		
	}
	
	private Date getObsRelatedDate(Set<Obs> obsSet, String conceptUuid) {
		Optional<Obs> foundObs = obsSet.stream()
		.filter(observation -> observation.getConcept().getUuid().compareTo(conceptUuid) == 0)  // Filtrer les observations par conceptId
		.findFirst();

		//System.out.println("FOUND ::::"+ foundObs.get().getValueDatetime());
		return foundObs.isPresent() ? foundObs.get().getValueDatetime() : new Date();
	}
	
	// Passing the patient and attribute value as a string
	private void updatePatientAttributes(Patient patient, String conceptValue, Date date) {
		
		PersonAttributeType personAttributeTypeStatus = Context.getPersonService().getPersonAttributeType(10);
		PersonAttribute patientAttributeStatus = new PersonAttribute(personAttributeTypeStatus, conceptValue);
		patient.addAttribute(patientAttributeStatus);
		
		PersonAttributeType patientAttributeStatusDate = Context.getPersonService().getPersonAttributeType(11);
		PersonAttribute personAttributeTypeStatusDate = new PersonAttribute(patientAttributeStatusDate, getDateAString(date));
		patient.addAttribute(personAttributeTypeStatusDate);
		
	}
	
	public String getDateAString(Date date) {
		SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
		return formatter.format(date);
	}
	
	/* private void udpdatePatientExtensionOnFhir(Patient patient) {
			
			IGenericClient fhirClient = Context.getRegisteredComponent("clientRegistryFhirClient", IGenericClient.class);
			org.hl7.fhir.r4.model.Patient fhirPatient = fhirClient.read().resource(org.hl7.fhir.r4.model.Patient.class)
			        .withId(patient.getUuid()).execute();
			// Update the patient resource
			System.out.println("fhir client::: " + fhirClient.toString());
			fhirClient.update().resource(fhirPatient).execute();
		}*/
	
}
