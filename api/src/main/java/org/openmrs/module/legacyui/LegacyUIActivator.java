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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.PatientService;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.ModuleActivator;
import org.openmrs.module.fhir2.api.FhirPatientIdentifierSystemService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * This class contains the logic that is run every time this module is either started or stopped.
 */
import org.springframework.stereotype.Component;

@Component
public class LegacyUIActivator extends BaseModuleActivator implements ApplicationContextAware {
	
	protected Log log = LogFactory.getLog(getClass());
	
	private static ApplicationContext applicationContext;
	
	@Autowired
	PatientService patientService;
	
	@Autowired
	FhirPatientIdentifierSystemService fhirPatientIdentifierSystemService;
	
	/**
	 * @see ModuleActivator#willRefreshContext()
	 */
	public void willRefreshContext() {
		log.info("Refreshing Legacy UI Module");
	}
	
	/**
	 * @see ModuleActivator#contextRefreshed()
	 */
	public void contextRefreshed() {
		log.info("Legacy UI Module refreshed");
	}
	
	/**
	 * @see ModuleActivator#willStart()
	 */
	public void willStart() {
		log.info("Starting Legacy UI Module");
	}
	
	/**
	 * @see ModuleActivator#started()
	 */
	public void started() {
		applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
		
		log.info("Legacy UI Module started");
	}
	
	/**
	 * @see ModuleActivator#willStop()
	 */
	public void willStop() {
		log.info("Stopping Legacy UI Module");
	}
	
	/**
	 * @see ModuleActivator#stopped()
	 */
	public void stopped() {
		log.info("Legacy UI Module stopped");
	}
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		// TODO Auto-generated method stub
		this.applicationContext = applicationContext;
	}
	
}
