/*******************************************************************************
 * Copyright (c) 2006, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.core.lrparser.tests.c99;

import org.eclipse.cdt.core.dom.ast.IASTComment;
import org.eclipse.cdt.core.dom.ast.IASTFileLocation;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.lrparser.c99.C99Language;
import org.eclipse.cdt.core.dom.lrparser.cpp.ISOCPPLanguage;
import org.eclipse.cdt.core.lrparser.tests.ParseHelper;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.parser.ParserLanguage;
import org.eclipse.cdt.core.parser.tests.ast2.CommentTests;
import org.eclipse.cdt.internal.core.parser.ParserException;

public class C99CommentTests extends CommentTests {

	 
    @Override
	protected IASTTranslationUnit parse( String code, ParserLanguage lang, boolean useGNUExtensions, boolean expectNoProblems )  throws ParserException {
    	ILanguage language = lang.isCPP() ? getCPPLanguage() : getC99Language();
    	return ParseHelper.parse(code, language, expectNoProblems);
    }
    
    
    @Override
	protected IASTTranslationUnit parse(String code, ParserLanguage lang,
			boolean useGNUExtensions, boolean expectNoProblems,
			boolean parseComments) throws ParserException {
		
    	ILanguage language = lang.isCPP() ? getCPPLanguage() : getC99Language();
    	return ParseHelper.commentParse(code, language);
    }

	protected ILanguage getC99Language() {
    	return C99Language.getDefault();
    }
	
	protected ILanguage getCPPLanguage() {
		return ISOCPPLanguage.getDefault();
	}
	
	
	public void testBug191266() throws Exception {
		StringBuffer sb = new StringBuffer();
		sb.append("#define MACRO 1000000000000  \n");
		sb.append("int x = MACRO;  \n");
		sb.append("//comment\n");
		String code = sb.toString();
		
		IASTTranslationUnit tu = parse(code, ParserLanguage.C, false, false, true);
		
		IASTComment[] comments = tu.getComments();
		assertEquals(1, comments.length);

		IASTFileLocation location = comments[0].getFileLocation();
		assertEquals(code.indexOf("//"), location.getNodeOffset());
		assertEquals("//comment".length(), location.getNodeLength());
	}
}
