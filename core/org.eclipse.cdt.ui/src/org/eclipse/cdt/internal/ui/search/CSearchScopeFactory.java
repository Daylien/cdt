/*******************************************************************************
 * Copyright (c) 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v0.5 
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v05.html
 * 
 * Contributors:
 *     IBM Corp. - Rational Software - initial implementation
 ******************************************************************************/
/*
 * Created on Jun 12, 2003
 */
package org.eclipse.cdt.internal.ui.search;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.search.ICSearchScope;
import org.eclipse.cdt.core.search.SearchEngine;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkingSet;

/**
 * @author aniefer
 *
 * To change the template for this generated type comment go to
 * Window>Preferences>Java>Code Generation>Code and Comments
 */
public class CSearchScopeFactory {
	private static CSearchScopeFactory fgInstance;
	private static ICSearchScope EMPTY_SCOPE= SearchEngine.createCSearchScope(new ICElement[]{});
	/**
	 * 
	 */
	public CSearchScopeFactory() {
		super();
	}
	
	public static CSearchScopeFactory getInstance() {
		if (fgInstance == null)
			fgInstance = new CSearchScopeFactory();
		return fgInstance;
	}
	/**
	 * @param sets
	 * @return
	 */
	public ICSearchScope createCSearchScope(IWorkingSet[] sets) {
		if (sets == null || sets.length < 1)
		return EMPTY_SCOPE;

		Set cElements= new HashSet(sets.length * 10);
		for (int i= 0; i < sets.length; i++)
			addCElements(cElements, sets[i]);
		return createCSearchScope(cElements);
	}
	/**
	 * @param cElements
	 * @return
	 */
	private ICSearchScope createCSearchScope(Set cElements) {
		return SearchEngine.createCSearchScope((ICElement[])cElements.toArray(new ICElement[cElements.size()]));
	}
	/**
	 * @param cElements
	 * @param set
	 */
	private void addCElements(Set cElements, IWorkingSet set) {
		if (set == null)
			return;
				
		IAdaptable[] elements= set.getElements();
		for (int i= 0; i < elements.length; i++) {
			if (elements[i] instanceof ICElement)
				addCElements(cElements, (ICElement)elements[i]);
			else
				addCElements(cElements, elements[i]);
		}
	}
	/**
	 * @param cElements
	 * @param adaptable
	 */
	private void addCElements(Set cElements, IAdaptable resource) {
		ICElement cElement= (ICElement)resource.getAdapter(ICElement.class);
		if (cElement == null)
			// not an ICElement resource
			return;
				
		addCElements(cElements, cElement);
	}
	/**
	 * @param cElements
	 * @param element
	 */
	private void addCElements(Set cElements, ICElement element) {
				cElements.add(element);
	}

	/**
	 * @param fStructuredSelection
	 * @return
	 */
	public ICSearchScope createCSearchScope(IStructuredSelection fStructuredSelection) {
		Set cElements = new HashSet( fStructuredSelection.size() );
		
		Iterator iter = fStructuredSelection.iterator();
		while( iter.hasNext() ){
			addCElements( cElements, (IAdaptable)iter.next() );
		}
		
		return createCSearchScope( cElements );
	}
	
}
