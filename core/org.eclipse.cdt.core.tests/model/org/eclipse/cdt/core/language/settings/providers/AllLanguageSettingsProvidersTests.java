/*******************************************************************************
 * Copyright (c) 2010, 2011 Andrew Gvozdev and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Andrew Gvozdev - Initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.core.language.settings.providers;

import junit.framework.Test;
import junit.framework.TestSuite;


public class AllLanguageSettingsProvidersTests {
	public static void main(String[] args) {
		junit.textui.TestRunner.run(suite());
	}

	public static Test suite() {
		TestSuite suite = new TestSuite(AllLanguageSettingsProvidersTests.class.getName());

		suite.addTest(LanguageSettingsExtensionsTests.suite());
		suite.addTest(LanguageSettingsManagerTests.suite());
		suite.addTest(LanguageSettingsSerializableTests.suite());
		suite.addTest(LanguageSettingsPersistenceProjectTests.suite());
		suite.addTest(LanguageSettingsScannerInfoProviderTests.suite());
		return suite;
	}
}
