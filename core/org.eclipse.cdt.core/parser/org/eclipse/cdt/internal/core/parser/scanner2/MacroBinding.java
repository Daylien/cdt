/*******************************************************************************
 * Copyright (c) 2004, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM - Initial API and implementation
 * Markus Schorn (Wind River Systems)
 *******************************************************************************/
package org.eclipse.cdt.internal.core.parser.scanner2;

import org.eclipse.cdt.core.dom.ILinkage;
import org.eclipse.cdt.core.dom.ast.DOMException;
import org.eclipse.cdt.core.dom.ast.IMacroBinding;
import org.eclipse.cdt.core.dom.ast.IScope;
import org.eclipse.cdt.internal.core.dom.Linkage;
import org.eclipse.cdt.internal.core.parser.scanner2.LocationMap._FunctionMacroDefinition;
import org.eclipse.core.runtime.PlatformObject;

public class MacroBinding extends PlatformObject implements IMacroBinding {

    private final char[] name;
    private final IScope scope;
    private final IScannerPreprocessorLog.IMacroDefinition definition;

    public MacroBinding( char [] name, IScope scope, IScannerPreprocessorLog.IMacroDefinition definition )
    {
        this.name = name;
        this.scope = scope;
        this.definition = definition;
    }
    
    public String getName() {
        return new String( name );
    }

    public char[] getNameCharArray() {
        return name;
    }

    public IScope getScope() throws DOMException {
        return scope;
    }

    /**
     * @return Returns the definition.
     */
    public IScannerPreprocessorLog.IMacroDefinition getDefinition() {
        return definition;
    }
    
	public ILinkage getLinkage() {
		return Linkage.NO_LINKAGE;
	}

	public char[] getExpansion() {
		return definition.getExpansion();
	}

	public boolean isFunctionStyle() {
		return definition instanceof _FunctionMacroDefinition;
	}

	public char[][] getParameterList() {
		if (isFunctionStyle()) {
			return ((_FunctionMacroDefinition) definition).getParms();
		}
		return null;
	}
}
