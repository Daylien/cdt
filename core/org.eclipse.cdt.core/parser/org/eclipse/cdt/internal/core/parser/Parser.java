/**********************************************************************
 * Copyright (c) 2002,2003 Rational Software Corporation and others.
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Common Public License v0.5
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v05.html
 * 
 * Contributors: 
 * Rational Software - Initial API and implementation
***********************************************************************/
package org.eclipse.cdt.internal.core.parser;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import org.eclipse.cdt.core.parser.Backtrack;
import org.eclipse.cdt.core.parser.EndOfFile;
import org.eclipse.cdt.core.parser.IParser;
import org.eclipse.cdt.core.parser.IProblemReporter;
import org.eclipse.cdt.core.parser.IScanner;
import org.eclipse.cdt.core.parser.ISourceElementRequestor;
import org.eclipse.cdt.core.parser.IToken;
import org.eclipse.cdt.core.parser.ITokenDuple;
import org.eclipse.cdt.core.parser.ITranslationResult;
import org.eclipse.cdt.core.parser.ParserFactory;
import org.eclipse.cdt.core.parser.ParserMode;
import org.eclipse.cdt.core.parser.ScannerException;
import org.eclipse.cdt.core.parser.ast.ASTAccessVisibility;
import org.eclipse.cdt.core.parser.ast.ASTClassKind;
import org.eclipse.cdt.core.parser.ast.ASTPointerOperator;
import org.eclipse.cdt.core.parser.ast.ASTSemanticException;
import org.eclipse.cdt.core.parser.ast.IASTASMDefinition;
import org.eclipse.cdt.core.parser.ast.IASTArrayModifier;
import org.eclipse.cdt.core.parser.ast.IASTClassSpecifier;
import org.eclipse.cdt.core.parser.ast.IASTCompilationUnit;
import org.eclipse.cdt.core.parser.ast.IASTDeclaration;
import org.eclipse.cdt.core.parser.ast.IASTElaboratedTypeSpecifier;
import org.eclipse.cdt.core.parser.ast.IASTEnumerationSpecifier;
import org.eclipse.cdt.core.parser.ast.IASTExpression;
import org.eclipse.cdt.core.parser.ast.IASTFactory;
import org.eclipse.cdt.core.parser.ast.IASTInitializerClause;
import org.eclipse.cdt.core.parser.ast.IASTLinkageSpecification;
import org.eclipse.cdt.core.parser.ast.IASTNamespaceAlias;
import org.eclipse.cdt.core.parser.ast.IASTNamespaceDefinition;
import org.eclipse.cdt.core.parser.ast.IASTOffsetableElement;
import org.eclipse.cdt.core.parser.ast.IASTScope;
import org.eclipse.cdt.core.parser.ast.IASTSimpleTypeSpecifier;
import org.eclipse.cdt.core.parser.ast.IASTTemplate;
import org.eclipse.cdt.core.parser.ast.IASTTemplateDeclaration;
import org.eclipse.cdt.core.parser.ast.IASTTemplateInstantiation;
import org.eclipse.cdt.core.parser.ast.IASTTemplateParameter;
import org.eclipse.cdt.core.parser.ast.IASTTemplateSpecialization;
import org.eclipse.cdt.core.parser.ast.IASTUsingDeclaration;
import org.eclipse.cdt.core.parser.ast.IASTUsingDirective;
import org.eclipse.cdt.core.parser.ast.IASTClassSpecifier.ClassNameType;
import org.eclipse.cdt.core.parser.ast.IASTExpression.Kind;
import org.eclipse.cdt.internal.core.model.IDebugLogConstants;
import org.eclipse.cdt.internal.core.model.Util;
/**
 * This is our first implementation of the IParser interface, serving as a parser for
 * ANSI C and C++.
 * 
 * From time to time we will make reference to the ANSI ISO specifications.
 * 
 * @author jcamelon
 */
public class Parser implements IParser
{
    private ClassNameType access;
    private static int DEFAULT_OFFSET = -1;
    // sentinel initial value for offsets 
    private int firstErrorOffset = DEFAULT_OFFSET;
    // offset where the first parse error occurred
   
    private ParserMode mode = ParserMode.COMPLETE_PARSE;
    // are we doing the high-level parse, or an in depth parse?
    private boolean parsePassed = true; // did the parse pass?
    private boolean cppNature = true; // true for C++, false for C
    private ISourceElementRequestor requestor = null;
    // new callback mechanism
    private IASTFactory astFactory = null; // ast factory
    private IProblemReporter problemReporter = null;
    private ITranslationResult unitResult = null;
    /**
     * This is the single entry point for setting parsePassed to 
     * false, and also making note what token offset we failed upon. 
     * 
     * @throws EndOfFile
     */
    protected void failParse() throws EndOfFile
    {
    	try
    	{
	        if (firstErrorOffset == DEFAULT_OFFSET)
	            firstErrorOffset = LA(1).getOffset();
    	} catch( EndOfFile eof )
    	{
    		throw eof;
    	}
    	finally
    	{
	        parsePassed = false;
    	}
    }
    /**
     * This is the standard cosntructor that we expect the Parser to be instantiated 
     * with.  
     * 
     * @param s				IScanner instance that has been initialized to the code input 
     * @param c				IParserCallback instance that will receive callbacks as we parse
     * @param quick			Are we asking for a high level parse or not? 
     */
    public Parser(
        IScanner scanner,
        ISourceElementRequestor callback,
        ParserMode mode,
        IProblemReporter problemReporter,
        ITranslationResult unitResult)
    {
        this.scanner = scanner;
        this.problemReporter = problemReporter;
        this.unitResult = unitResult;
        requestor = callback;
        this.mode = mode;
        astFactory = ParserFactory.createASTFactory(mode);
        scanner.setASTFactory(astFactory);
    }
    // counter that keeps track of the number of times Parser.parse() is called
    private static int parseCount = 0;
    /* (non-Javadoc)
     * @see org.eclipse.cdt.internal.core.parser.IParser#parse()
     */
    public boolean parse()
    {
        long startTime = System.currentTimeMillis();
        translationUnit();
        onParseEnd();
        // For the debuglog to take place, you have to call
        // Util.setDebugging(true);
        // Or set debug to true in the core plugin preference 
        Util.debugLog(
            "Parse "
                + (++parseCount)
                + ": "
                + (System.currentTimeMillis() - startTime)
                + "ms"
                + (parsePassed ? "" : " - parse failure"), IDebugLogConstants.PARSER);
        return parsePassed;
    }
    public void onParseEnd()
    {
        scanner.onParseEnd();
    }
    /**
     * This is the top-level entry point into the ANSI C++ grammar.  
     * 
     * translationUnit  : (declaration)*
     */
    protected void translationUnit()
    {
        IASTCompilationUnit compilationUnit =
            astFactory.createCompilationUnit();

		compilationUnit.enterScope( requestor );            
        IToken lastBacktrack = null;
        IToken checkToken = null;
        while (true)
        {
            try
            {
                checkToken = LA(1);
                declaration(compilationUnit, null);
                if (LA(1) == checkToken)
                    errorHandling();
            }
            catch (EndOfFile e)
            {
                // Good
                break;
            }
            catch (Backtrack b)
            {
                try
                {
                    // Mark as failure and try to reach a recovery point
                    failParse();
                    if (lastBacktrack != null && lastBacktrack == LA(1))
                    {
                        // we haven't progressed from the last backtrack
                        // try and find tne next definition
                        errorHandling();
                    }
                    else
                    {
                        // start again from here
                        lastBacktrack = LA(1);
                    }
                }
                catch (EndOfFile e)
                {
                    break;
                }
            }
            catch (Exception e)
            {
                try {
					failParse();
				} catch (EndOfFile e1) {
				} 
                break;
            }
        }
        compilationUnit.exitScope( requestor );
    }
    /**
     * This function is called whenever we encounter and error that we cannot backtrack out of and we 
     * still wish to try and continue on with the parse to do a best-effort parse for our client. 
     * 
     * @throws EndOfFile  	We can potentially hit EndOfFile here as we are skipping ahead.  
     */
    protected void errorHandling() throws EndOfFile
    {
        failParse();
        consume();
        int depth = 0;
        while (!((LT(1) == IToken.tSEMI && depth == 0)
            || (LT(1) == IToken.tRBRACE && depth == 1)))
        {
            switch (LT(1))
            {
                case IToken.tLBRACE :
                    ++depth;
                    break;
                case IToken.tRBRACE :
                    --depth;
                    break;
            }
            consume();
        }
        // eat the SEMI/RBRACE as well
        consume();
    }
    /**
     * The merger of using-declaration and using-directive in ANSI C++ grammar.  
     * 
     * using-declaration:
     *	using typename? ::? nested-name-specifier unqualified-id ;
     *	using :: unqualified-id ;
     * using-directive:
     *  using namespace ::? nested-name-specifier? namespace-name ;
     * 
     * @param container		Callback object representing the scope these definitions fall into. 
     * @throws Backtrack	request for a backtrack
     */
    protected void usingClause(IASTScope scope)
        throws Backtrack
    {
        IToken firstToken = consume(IToken.t_using);
        if (LT(1) == IToken.t_namespace)
        {
            // using-directive
            consume(IToken.t_namespace);
            // optional :: and nested classes handled in name
            TokenDuple duple = null;
            if (LT(1) == IToken.tIDENTIFIER || LT(1) == IToken.tCOLONCOLON)
                duple = name();
            else
                throw backtrack;
            if (LT(1) == IToken.tSEMI)
            {
                IToken last = consume(IToken.tSEMI);
                IASTUsingDirective astUD = null; 
                
                try
                {
                    astUD = astFactory.createUsingDirective(scope, duple, firstToken.getOffset(), last.getEndOffset());
                }
                catch (ASTSemanticException e)
                {                	
                	failParse();
                	throw backtrack;
                }
                astUD.acceptElement(requestor);
                return;
            }
            else
            {
                throw backtrack;
            }
        }
        else
        {
            boolean typeName = false;
            if (LT(1) == IToken.t_typename)
            {
                typeName = true;
                consume(IToken.t_typename);
            }
            TokenDuple name = null;
            if (LT(1) == IToken.tIDENTIFIER || LT(1) == IToken.tCOLONCOLON)
            {
                //	optional :: and nested classes handled in name
                name = name();
            }
            else
            {
                throw backtrack;
            }
            if (LT(1) == IToken.tSEMI)
            {
                IToken last = consume(IToken.tSEMI);
                IASTUsingDeclaration declaration = null;
                try
                {
                    declaration =
                        astFactory.createUsingDeclaration(
                            scope,
                            typeName,
                            name,
                            firstToken.getOffset(),
                            last.getEndOffset());
                }
                catch (ASTSemanticException e)
                {
                	failParse();
                	throw backtrack;
                }
                declaration.acceptElement( requestor );
            }
            else
            {
                throw backtrack;
            }
        }
    }
    /**
     * Implements Linkage specification in the ANSI C++ grammar. 
     * 
     * linkageSpecification
     * : extern "string literal" declaration
     * | extern "string literal" { declaration-seq } 
     * 
     * @param container Callback object representing the scope these definitions fall into.
     * @throws Backtrack	request for a backtrack
     */
    protected void linkageSpecification(IASTScope scope)
        throws Backtrack
    {
        IToken firstToken = consume(IToken.t_extern);
        if (LT(1) != IToken.tSTRING)
            throw backtrack;
        IToken spec = consume(IToken.tSTRING);
  
        if (LT(1) == IToken.tLBRACE)
        {
            consume(IToken.tLBRACE);
            IASTLinkageSpecification linkage =
                astFactory.createLinkageSpecification(scope, spec.getImage(), firstToken.getOffset());
            
            linkage.enterScope( requestor );    
            linkageDeclarationLoop : while (LT(1) != IToken.tRBRACE)
            {
                IToken checkToken = LA(1);
                switch (LT(1))
                {
                    case IToken.tRBRACE :
                        consume(IToken.tRBRACE);
                        break linkageDeclarationLoop;
                    default :
                        try
                        {
                            declaration(linkage, null);
                        }
                        catch (Backtrack bt)
                        {
                            failParse();
                            if (checkToken == LA(1))
                                errorHandling();
                        }
                }
                if (checkToken == LA(1))
                    errorHandling();
            }
            // consume the }
            IToken lastToken = consume();
            linkage.setEndingOffset(lastToken.getEndOffset());
            linkage.exitScope( requestor );
        }
        else // single declaration
            {
            IASTLinkageSpecification linkage =
                astFactory.createLinkageSpecification(scope, spec.getImage(), firstToken.getOffset());
			linkage.enterScope( requestor );
            declaration(linkage, null);
			linkage.exitScope( requestor );
        }
    }
    /**
     * 
     * Represents the emalgamation of template declarations, template instantiations and 
     * specializations in the ANSI C++ grammar.  
     * 
     * template-declaration:	export? template < template-parameter-list > declaration
     * explicit-instantiation:	template declaration
     * explicit-specialization:	template <> declaration
     *  
     * @param container			Callback object representing the scope these definitions fall into.
     * @throws Backtrack		request for a backtrack
     */
    protected void templateDeclaration(IASTScope scope)
        throws Backtrack
    {
        IToken firstToken = null;
        boolean exported = false; 
        if (LT(1) == IToken.t_export)
        {
        	exported = true;
            firstToken = consume(IToken.t_export);
            consume(IToken.t_template);
        }
        else
            firstToken = consume(IToken.t_template);
        if (LT(1) != IToken.tLT)
        {
            // explicit-instantiation
            IASTTemplateInstantiation templateInstantiation =
                astFactory.createTemplateInstantiation(
                    scope,
                    firstToken.getOffset());
            templateInstantiation.enterScope( requestor );
            declaration(scope, templateInstantiation);
            templateInstantiation.setEndingOffset(lastToken.getEndOffset());
			templateInstantiation.exitScope( requestor );
 
            return;
        }
        else
        {
            consume(IToken.tLT);
            if (LT(1) == IToken.tGT)
            {
                consume(IToken.tGT);
                // explicit-specialization
                
                IASTTemplateSpecialization templateSpecialization =
                    astFactory.createTemplateSpecialization(
                        scope,
                        firstToken.getOffset());
				templateSpecialization.enterScope(requestor);
                declaration(scope, templateSpecialization);
                templateSpecialization.setEndingOffset(
                    lastToken.getEndOffset());
                templateSpecialization.exitScope(requestor);
                return;
            }
        }
        
        try
        {
            List parms = templateParameterList(scope);
            consume(IToken.tGT);
            IASTTemplateDeclaration templateDecl = astFactory.createTemplateDeclaration( scope, parms, exported, firstToken.getOffset() );
            templateDecl.enterScope( requestor );
            declaration(scope, templateDecl );
			templateDecl.setEndingOffset(
				lastToken.getEndOffset() );
			templateDecl.exitScope( requestor );
            
        }
        catch (Backtrack bt)
        {
            throw bt;
        }
    }
    /**
     * 
     * 
     * 
    	 * template-parameter-list:	template-parameter
     *							template-parameter-list , template-parameter
     * template-parameter:		type-parameter
     *							parameter-declaration
     * type-parameter:			class identifier?
     *							class identifier? = type-id
     * 							typename identifier?
     * 							typename identifier? = type-id
     *							template < template-parameter-list > class identifier?
     *							template < template-parameter-list > class identifier? = id-expression
     * template-id:				template-name < template-argument-list?>
     * template-name:			identifier
     * template-argument-list:	template-argument
     *							template-argument-list , template-argument
     * template-argument:		assignment-expression
     *							type-id
     *							id-expression
     *
     * @param templateDeclaration		Callback's templateDeclaration which serves as a scope to this list.  
     * @throws Backtrack				request for a backtrack
     */
    protected List templateParameterList(IASTScope scope)
        throws Backtrack
    {
        // if we have gotten this far then we have a true template-declaration
        // iterate through the template parameter list
        List returnValue = new ArrayList();
 
        for (;;)
        {
            if (LT(1) == IToken.tGT)
                return returnValue;
            if (LT(1) == IToken.t_class || LT(1) == IToken.t_typename)
            {
                IASTTemplateParameter.ParamKind kind =
                    (consume().getType() == IToken.t_class)
                        ? IASTTemplateParameter.ParamKind.CLASS
                        : IASTTemplateParameter.ParamKind.TYPENAME;
				
				IToken id = null;
				ITokenDuple typeId = null;
                try
                {
                    if (LT(1) == IToken.tIDENTIFIER) // optional identifier
                    {
                        id = identifier();
                        
                        if (LT(1) == IToken.tASSIGN) // optional = type-id
                        {
                            consume(IToken.tASSIGN);
                            typeId = typeId(); // type-id
                        }
                    }

                }
                catch (Backtrack bt)
                {
                    throw bt;
                }
				returnValue.add(
					astFactory.createTemplateParameter(
						kind,
						( id == null )? "" : id.getImage(),
						(typeId == null) ? null : typeId.toString(),
						null,
						null));

            }
            else if (LT(1) == IToken.t_template)
            {
                IToken kind = consume(IToken.t_template);
                consume(IToken.tLT);

                List subResult = templateParameterList(scope);
                consume(IToken.tGT);
                consume(IToken.t_class);
                IToken optionalId = null;
                ITokenDuple optionalTypeId = null;
                if (LT(1) == IToken.tIDENTIFIER) // optional identifier
                {
                    optionalId = identifier();
   
                    if (LT(1) == IToken.tASSIGN) // optional = type-id
                    {
                        consume(IToken.tASSIGN);
                        optionalTypeId = typeId();
    
                    }
                }
 
                returnValue.add(
                    astFactory.createTemplateParameter(
                        IASTTemplateParameter.ParamKind.TEMPLATE_LIST,
                        ( optionalId == null )? "" : optionalId.getImage(),
                        ( optionalTypeId == null )  ? "" : optionalTypeId.toString(),
                        null,
                        subResult));
            }
            else if (LT(1) == IToken.tCOMMA)
            {
                consume(IToken.tCOMMA);
                continue;
            }
            else
            {
                ParameterCollection c = new ParameterCollection();
                parameterDeclaration(c, scope);
                DeclarationWrapper wrapper =
                    (DeclarationWrapper)c.getParameters().get(0);
                Declarator declarator =
                    (Declarator)wrapper.getDeclarators().next();
                returnValue.add(
                    astFactory.createTemplateParameter(
                        IASTTemplateParameter.ParamKind.PARAMETER,
                        null,
                        null,
                        astFactory.createParameterDeclaration(
                            wrapper.isConst(),
                            wrapper.isVolatile(),
                            wrapper.getTypeSpecifier(),
                            declarator.getPtrOps(),
                            declarator.getArrayModifiers(),
                            null, null, declarator.getName() == null
                                            ? ""
                                            : declarator.getName(), declarator.getInitializerClause()),
                        null));
            }
        }
    }
    /**
     * The most abstract construct within a translationUnit : a declaration.  
     * 
     * declaration
     * : {"asm"} asmDefinition
     * | {"namespace"} namespaceDefinition
     * | {"using"} usingDeclaration
     * | {"export"|"template"} templateDeclaration
     * | {"extern"} linkageSpecification
     * | simpleDeclaration
     * 
     * Notes:
     * - folded in blockDeclaration
     * - merged alternatives that required same LA
     *   - functionDefinition into simpleDeclaration
     *   - namespaceAliasDefinition into namespaceDefinition
     *   - usingDirective into usingDeclaration
     *   - explicitInstantiation and explicitSpecialization into
     *       templateDeclaration
     * 
     * @param container		IParserCallback object which serves as the owner scope for this declaration.  
     * @throws Backtrack	request a backtrack
     */
    protected void declaration(
        IASTScope scope,
        IASTTemplate ownerTemplate)
        throws Backtrack
    {
        switch (LT(1))
        {
            case IToken.t_asm :
                IToken first = consume(IToken.t_asm);
                consume(IToken.tLPAREN);
                String assembly = consume(IToken.tSTRING).getImage();
                consume(IToken.tRPAREN);
                IToken last = consume(IToken.tSEMI);
                IASTASMDefinition asmDefinition =
                    astFactory.createASMDefinition(
                        scope,
                        assembly,
                        first.getOffset(),
                        last.getEndOffset());
                // if we made it this far, then we have all we need 
                // do the callback
 				asmDefinition.acceptElement(requestor);
                return;
            case IToken.t_namespace :
                namespaceDefinition(scope);
                return;
            case IToken.t_using :
                usingClause(scope);
                return;
            case IToken.t_export :
            case IToken.t_template :
                templateDeclaration(scope);
                return;
            case IToken.t_extern :
                if (LT(2) == IToken.tSTRING)
                {
                    linkageSpecification(scope);
                    return;
                }
            default :
                simpleDeclarationStrategyUnion(scope, ownerTemplate);
        }
    }
    protected void simpleDeclarationStrategyUnion(
        IASTScope scope,
        IASTTemplate ownerTemplate)
        throws EndOfFile, Backtrack
    {
        IToken mark = mark();
        try
        {
            simpleDeclaration(
                SimpleDeclarationStrategy.TRY_CONSTRUCTOR,
                false,
                scope,
                ownerTemplate);
            // try it first with the original strategy
        }
        catch (Backtrack bt)
        {
            // did not work 
            backup(mark);
            
            try
            {  
            	simpleDeclaration(
                	SimpleDeclarationStrategy.TRY_FUNCTION,
	                false,
    	            scope,
        	        ownerTemplate);
            }
            catch( Backtrack bt2 )
            {
            	backup( mark ); 

				simpleDeclaration(
					SimpleDeclarationStrategy.TRY_VARIABLE,
					false,
					scope,
					ownerTemplate);
            }
        }
    }
    /**
     *  Serves as the namespace declaration portion of the ANSI C++ grammar.  
     * 
     * 	namespace-definition:
     *		namespace identifier { namespace-body } | namespace { namespace-body }
     *	 namespace-body:
     *		declaration-seq?
     * @param container		IParserCallback object which serves as the owner scope for this declaration.  
     * @throws Backtrack	request a backtrack
    
     */
    protected void namespaceDefinition(IASTScope scope)
        throws Backtrack
    {
        IToken first = consume(IToken.t_namespace);
 
        IToken identifier = null;
        // optional name 		
        if (LT(1) == IToken.tIDENTIFIER)
            identifier = identifier();
        
        if (LT(1) == IToken.tLBRACE)
        {
            consume();
            IASTNamespaceDefinition namespaceDefinition = null;
            try
            {
                namespaceDefinition = 
                    astFactory.createNamespaceDefinition(
                        scope,
                        (identifier == null ? "" : identifier.getImage()),
                        first.getOffset(),
                        (identifier == null ? first.getOffset() : identifier.getOffset()));
            }
            catch (ASTSemanticException e)
            {
				failParse();
				throw backtrack;
            }
            namespaceDefinition.enterScope( requestor );
            namepsaceDeclarationLoop : while (LT(1) != IToken.tRBRACE)
            {
                IToken checkToken = LA(1);
                switch (LT(1))
                {
                    case IToken.tRBRACE :
                        //consume(Token.tRBRACE);
                        break namepsaceDeclarationLoop;
                    default :
                        try
                        {
                            declaration(namespaceDefinition, null);
                        }
                        catch (Backtrack bt)
                        {
                            failParse();
                            if (checkToken == LA(1))
                                errorHandling();
                        }
                }
                if (checkToken == LA(1))
                    errorHandling();
            }
            // consume the }
            IToken last = consume(IToken.tRBRACE);
 
            namespaceDefinition.setEndingOffset(
                last.getOffset() + last.getLength());
            namespaceDefinition.exitScope( requestor );
        }
        else if( LT(1) == IToken.tASSIGN )
        {
        	consume( IToken.tASSIGN );
        	
			if( identifier == null )
				throw backtrack;

        	ITokenDuple duple = name();
        	        	
        	IASTNamespaceAlias alias = null; 
        	
        	try
            {
                alias = astFactory.createNamespaceAlias( 
                	scope, identifier.toString(), duple, first.getOffset(), 
                	identifier.getOffset(), duple.getLastToken().getEndOffset() );
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        else
        {
            throw backtrack;
        }
    }
    /**
     * Serves as the catch-all for all complicated declarations, including function-definitions.  
     * 
     * simpleDeclaration
     * : (declSpecifier)* (initDeclarator ("," initDeclarator)*)? 
     *     (";" | { functionBody }
     * 
     * Notes:
     * - append functionDefinition stuff to end of this rule
     * 
     * To do:
     * - work in functionTryBlock
     * 
     * @param container			IParserCallback object which serves as the owner scope for this declaration.
     * @param tryConstructor	true == take strategy1 (constructor ) : false == take strategy 2 ( pointer to function)
     * @param forKR             Is this for K&R-style parameter declaration (true) or simple declaration (false) 
     * @throws Backtrack		request a backtrack
     */
    protected void simpleDeclaration(
        SimpleDeclarationStrategy strategy,
        boolean forKR,
        IASTScope scope,
        IASTTemplate ownerTemplate)
        throws Backtrack
    {
        DeclarationWrapper sdw =
            new DeclarationWrapper(scope, LA(1).getOffset(), ownerTemplate);

        declSpecifierSeq(false, strategy == SimpleDeclarationStrategy.TRY_CONSTRUCTOR, sdw, forKR );
        try
        {       
	        if (sdw.getTypeSpecifier() == null && sdw.getSimpleType() != IASTSimpleTypeSpecifier.Type.UNSPECIFIED )
	            sdw.setTypeSpecifier(
	                astFactory.createSimpleTypeSpecifier(
	                    scope,
	                    sdw.getSimpleType(),
	                    sdw.getName(),
	                    sdw.isShort(),
	                    sdw.isLong(),
	                    sdw.isSigned(),
	                    sdw.isUnsigned(), sdw.isTypeNamed()));
        } catch( ASTSemanticException se )
        {
			failParse();
			throw backtrack;
        }
        
        Declarator declarator = null;
        if (LT(1) != IToken.tSEMI)
        {
            declarator = initDeclarator(sdw, forKR, strategy);
                
            while (LT(1) == IToken.tCOMMA)
            {
                consume();
                initDeclarator(sdw, forKR, strategy);
            }
        }


        boolean done = false;
        boolean hasFunctionBody = false;
        switch (LT(1))
        {
            case IToken.tSEMI :
                consume(IToken.tSEMI);
                done = true;
                break;
            case IToken.tCOLON :
                if (forKR)
                    throw backtrack;
                ctorInitializer(declarator);
                // Falling through on purpose
            case IToken.tLBRACE :
                if (forKR)
                    throw backtrack;
                declarator.hasFunctionBody(true);
                hasFunctionBody = true;
                break;
            default :
                throw backtrack;
        }
        
        if( forKR ) return;
        
        List l = null; 
        try
        {
            l = sdw.createASTNodes(astFactory);
        }
        catch (ASTSemanticException e)
        {
			failParse();
			throw backtrack;
        }
        Iterator i = l.iterator();
        if (hasFunctionBody && l.size() != 1)
        {
            failParse();
            throw backtrack; //TODO Should be an IProblem
        }
        if (i.hasNext()) // no need to do this unless we have a declarator
        {
            if (!hasFunctionBody)
            {
                while (i.hasNext())
                {
                    IASTDeclaration declaration = (IASTDeclaration)i.next();
                    ((IASTOffsetableElement)declaration).setEndingOffset(
                        lastToken.getEndOffset());
                    declaration.acceptElement( requestor );
                }
            }
            else
            {
                IASTDeclaration declaration = (IASTDeclaration)i.next();
                declaration.enterScope( requestor );
   
                handleFunctionBody(declarator);
				((IASTOffsetableElement)declaration).setEndingOffset(
					lastToken.getEndOffset());
  
  				declaration.exitScope( requestor );
            }
        }
        else
        {
            astFactory
                .createTypeSpecDeclaration(
                    sdw.getScope(),
                    sdw.getTypeSpecifier(),
                    ownerTemplate,
                    sdw.getStartingOffset(),
                    lastToken.getEndOffset())
                .acceptElement(requestor);
        }
        
    }
    protected void handleFunctionBody(Declarator d) throws Backtrack, EndOfFile
    {
        if ( mode == ParserMode.QUICK_PARSE ) // TODO - Enable parsing within function bodies i.e. mode == ParserMode.QUICK_PARSE)
        {
            // speed up the parser by skiping the body
            // simply look for matching brace and return
            consume(IToken.tLBRACE);
            int depth = 1;
            while (depth > 0)
            {
                switch (consume().getType())
                {
                    case IToken.tRBRACE :
                        --depth;
                        break;
                    case IToken.tLBRACE :
                        ++depth;
                        break;
                }
            }
        }
        else
        {
            functionBody();
        }
    }
    /**
     * This method parses a constructor chain 
     * ctorinitializer:	 : meminitializerlist
     * meminitializerlist: meminitializer | meminitializer , meminitializerlist
     * meminitializer: meminitializerid | ( expressionlist? ) 
     * meminitializerid:	::? nestednamespecifier?
     * 						classname
     * 						identifier
     * @param declarator	IParserCallback object that represents the declarator (constructor) that owns this initializer
     * @throws Backtrack	request a backtrack
     */
    protected void ctorInitializer(Declarator d)
        throws Backtrack
    {
        consume(IToken.tCOLON);

        try
        {
            for (;;)
            {
                if (LT(1) == IToken.tLBRACE)
                    break;


                ITokenDuple duple = name();

                consume(IToken.tLPAREN);
                IASTExpression expressionList = null;

                expressionList = expression(d.getDeclarationWrapper().getScope());

                consume(IToken.tRPAREN);

                try
                {
                    d.addConstructorMemberInitializer(
                        astFactory.createConstructorMemberInitializer(
                            d.getDeclarationWrapper().getScope(),
                            duple, expressionList));
                }
                catch (ASTSemanticException e)
                {
                    failParse();
                    throw backtrack;
                }
                if (LT(1) == IToken.tLBRACE)
                    break;
                consume(IToken.tCOMMA);
            }
        }
        catch (Backtrack bt)
        {
 
            throw backtrack;
        }

    }
    /**
     * This routine parses a parameter declaration 
     * 
     * @param containerObject	The IParserCallback object representing the parameterDeclarationClause owning the parm. 
     * @throws Backtrack		request a backtrack
     */
    protected void parameterDeclaration(
        IParameterCollection collection, IASTScope scope)
        throws Backtrack
    {
        IToken current = LA(1);
 
        DeclarationWrapper sdw =
            new DeclarationWrapper(scope, current.getOffset(), null);
        declSpecifierSeq(true, false, sdw, false);
        try
        {
	        if (sdw.getTypeSpecifier() == null
	            && sdw.getSimpleType()
	                != IASTSimpleTypeSpecifier.Type.UNSPECIFIED)
	            sdw.setTypeSpecifier(
	                astFactory.createSimpleTypeSpecifier(
	                    scope,
	                    sdw.getSimpleType(),
	                    sdw.getName(),
	                    sdw.isShort(),
	                    sdw.isLong(),
	                    sdw.isSigned(),
	                    sdw.isUnsigned(), sdw.isTypeNamed()));
        }
        catch( ASTSemanticException se ) { 
			throw backtrack;
		}
        
        if (LT(1) != IToken.tSEMI)
           initDeclarator(sdw, false, SimpleDeclarationStrategy.TRY_FUNCTION );
 
        if (current == LA(1))
            throw backtrack;
        collection.addParameter(sdw);
    }
    /**
     * This class represents the state and strategy for parsing declarationSpecifierSequences
     */
    private class Flags
    {
        private boolean encounteredTypename = false;
        // have we encountered a typeName yet?
        private boolean encounteredRawType = false;
        // have we encountered a raw type yet?
        private final boolean parm;
        // is this for a simpleDeclaration or parameterDeclaration?
        private final boolean constructor;
        // are we attempting the constructor strategy?
        public Flags(boolean parm, boolean c)
        {
            this.parm = parm;
            constructor = c;
        }
        /**
         * @return	true if we have encountered a simple type up to this point, false otherwise
         */
        public boolean haveEncounteredRawType()
        {
            return encounteredRawType;
        }
        /**
         * @return  true if we have encountered a typename up to this point, false otherwise
         */
        public boolean haveEncounteredTypename()
        {
            return encounteredTypename;
        }
        /**
         * @param b - set to true if we encounter a raw type (int, short, etc.)
         */
        public void setEncounteredRawType(boolean b)
        {
            encounteredRawType = b;
        }
        /**
         * @param b - set to true if we encounter a typename
         */
        public void setEncounteredTypename(boolean b)
        {
            encounteredTypename = b;
        }
        /**
         * @return true if we are parsing for a ParameterDeclaration
         */
        public boolean isForParameterDeclaration()
        {
            return parm;
        }
        /**
         * @return whether or not we are attempting the constructor strategy or not 
         */
        public boolean isForConstructor()
        {
            return constructor;
        }
    }
    /**
     * @param flags            input flags that are used to make our decision 
     * @return                 whether or not this looks like a constructor (true or false)
     * @throws EndOfFile       we could encounter EOF while looking ahead
     */
    private boolean lookAheadForConstructorOrConversion(Flags flags)
        throws EndOfFile
    {
        if (flags.isForParameterDeclaration())
            return false;
        if (LT(2) == IToken.tLPAREN && flags.isForConstructor())
            return true;
        boolean continueProcessing = true;
        // Portions of qualified name
        // ...::secondLastID<template-args>::lastID ...
        int secondLastIDTokenPos = -1;
        int lastIDTokenPos = 1;
        int tokenPos = 2;
        do
        {
            if (LT(tokenPos) == IToken.tLT)
            {
                // a case for template instantiation, like CFoobar<A,B>::CFoobar
                tokenPos++;
                // until we get all the names sorted out
                int depth = 1;
                while (depth > 0)
                {
                    switch (LT(tokenPos++))
                    {
                        case IToken.tGT :
                            --depth;
                            break;
                        case IToken.tLT :
                            ++depth;
                            break;
                    }
                }
            }
            if (LT(tokenPos) == IToken.tCOLONCOLON)
            {
                tokenPos++;
                switch (LT(tokenPos))
                {
                    case IToken.tCOMPL : // for destructors
                    case IToken.t_operator : // for conversion operators
                        return true;
                    case IToken.tIDENTIFIER :
                        secondLastIDTokenPos = lastIDTokenPos;
                        lastIDTokenPos = tokenPos;
                        tokenPos++;
                        break;
                    default :
                        // Something unexpected after ::
                        return false;
                }
            }
            else
            {
                continueProcessing = false;
            }
        }
        while (continueProcessing);
        // for constructors
        if (secondLastIDTokenPos < 0)
            return false;
        String secondLastID = LA(secondLastIDTokenPos).getImage();
        String lastID = LA(lastIDTokenPos).getImage();
        return secondLastID.equals(lastID);
    }
    /**
     * @param flags			input flags that are used to make our decision 
     * @return				whether or not this looks like a a declarator follows
     * @throws EndOfFile	we could encounter EOF while looking ahead
     */
    private boolean lookAheadForDeclarator(Flags flags) throws EndOfFile
    {
        return flags.haveEncounteredTypename()
            && ((LT(2) != IToken.tIDENTIFIER
                || (LT(3) != IToken.tLPAREN && LT(3) != IToken.tASSIGN))
                && !LA(2).isPointer());
    }
    private void callbackSimpleDeclToken(Flags flags) throws Backtrack
    {
        flags.setEncounteredRawType(true);
        consume(); 
    }
    /**
     * This function parses a declaration specifier sequence, as according to the ANSI C++ spec. 
     * 
     * declSpecifier
     * : "auto" | "register" | "static" | "extern" | "mutable"
     * | "inline" | "virtual" | "explicit"
     * | "char" | "wchar_t" | "bool" | "short" | "int" | "long"
     * | "signed" | "unsigned" | "float" | "double" | "void"
     * | "const" | "volatile"
     * | "friend" | "typedef"
     * | ("typename")? name
     * | {"class"|"struct"|"union"} classSpecifier
     * | {"enum"} enumSpecifier
     * 
     * Notes:
     * - folded in storageClassSpecifier, typeSpecifier, functionSpecifier
     * - folded elaboratedTypeSpecifier into classSpecifier and enumSpecifier
     * - find template names in name
     * 
     * @param decl				IParserCallback object representing the declaration that owns this specifier sequence
     * @param parm				Is this for a parameter declaration (true) or simple declaration (false)
     * @param tryConstructor	true for constructor, false for pointer to function strategy
     * @throws Backtrack		request a backtrack
     */
    protected void declSpecifierSeq(
        boolean parm,
        boolean tryConstructor,
        DeclarationWrapper sdw, 
        boolean forKR )
        throws Backtrack
    {
        Flags flags = new Flags(parm, tryConstructor);
        IToken typeNameBegin = null;
        IToken typeNameEnd = null;
        declSpecifiers : for (;;)
        {
            switch (LT(1))
            {
                case IToken.t_inline :
                	consume(); 
                    sdw.setInline(true);
                    break;
                case IToken.t_auto :
					consume(); 
                    sdw.setAuto(true);
                    break;
                case IToken.t_register :
                    sdw.setRegister(true);
					consume(); 
    	            break;
                case IToken.t_static :
                    sdw.setStatic(true);
					consume(); 
    		        break;
                case IToken.t_extern :
                    sdw.setExtern(true);
					consume(); 
                    break;
                case IToken.t_mutable :
                    sdw.setMutable(true);
					consume(); 
                    break;
                case IToken.t_virtual :
                    sdw.setVirtual(true);
					consume(); 
                    break;
                case IToken.t_explicit :
                    sdw.setExplicit(true);
					consume(); 
                    break;
                case IToken.t_typedef :
                    sdw.setTypedef(true);
					consume(); 
                    break;
                case IToken.t_friend :
                    sdw.setFriend(true);
					consume(); 
                    break;
                case IToken.t_const :
                    sdw.setConst(true);
					consume(); 
                    break;
                case IToken.t_volatile :
                    sdw.setVolatile(true);
					consume(); 
                    break;
                case IToken.t_signed :
                    sdw.setSigned(true);
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
					sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.INT);
                    break;
                case IToken.t_unsigned :
                    sdw.setUnsigned(true);
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
					sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.INT);
                    break;
                case IToken.t_short :
                    sdw.setShort(true);
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
					sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.INT);
                    break;
                case IToken.t_long :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
					sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.INT);
                    sdw.setLong(true);
                    break;
                case IToken.t_char :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.CHAR);
                    break;
                case IToken.t_wchar_t :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(
                        IASTSimpleTypeSpecifier.Type.WCHAR_T);
                    break;
                case IToken.t_bool :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.BOOL);
                    break;
                case IToken.t_int :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.INT);
                    break;
                case IToken.t_float :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.FLOAT);
                    break;
                case IToken.t_double :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(
                        IASTSimpleTypeSpecifier.Type.DOUBLE);
                    break;
                case IToken.t_void :
                    if (typeNameBegin == null)
                        typeNameBegin = LA(1);
                    typeNameEnd = LA(1);
                    callbackSimpleDeclToken(flags);
                    sdw.setSimpleType(IASTSimpleTypeSpecifier.Type.VOID);
                    break;
                case IToken.t_typename :
                    sdw.setTypenamed(true);
                    consume(IToken.t_typename ); 
                    IToken first = LA(1);
                    IToken last = null;
                    last = name().getLastToken();
                    if (LT(1) == IToken.t_template)
                    {
                        consume(IToken.t_template);
                        last = templateId();
                    }
                    ITokenDuple duple = new TokenDuple(first, last);
                    sdw.setTypeName(duple);
      
                    break;
                case IToken.tCOLONCOLON :
                    consume(IToken.tCOLONCOLON);
                    // handle nested later:
                case IToken.tIDENTIFIER :
                    // TODO - Kludgy way to handle constructors/destructors
                    // handle nested later:
                    if (flags.haveEncounteredRawType())
                    {
                        if (typeNameBegin != null)
                            sdw.setTypeName(
                                new TokenDuple(typeNameBegin, typeNameEnd));
                        return;
                    }
                    if (parm && flags.haveEncounteredTypename())
                    {
                        if (typeNameBegin != null)
                            sdw.setTypeName(
                                new TokenDuple(typeNameBegin, typeNameEnd));
                        return;
                    }
                    if (lookAheadForConstructorOrConversion(flags))
                    {
                        if (typeNameBegin != null)
                            sdw.setTypeName(
                                new TokenDuple(typeNameBegin, typeNameEnd));
                        return;
                    }
                    if (lookAheadForDeclarator(flags))
                    {
                        if (typeNameBegin != null)
                            sdw.setTypeName(
                                new TokenDuple(typeNameBegin, typeNameEnd));
                        return;
                    }
 
                    ITokenDuple d = name();
                    sdw.setTypeName(d);
                    sdw.setSimpleType( IASTSimpleTypeSpecifier.Type.CLASS_OR_TYPENAME ); 
                    flags.setEncounteredTypename(true);
                    break;
                case IToken.t_class :
                case IToken.t_struct :
                case IToken.t_union :
                    if (!parm && !forKR )
                    {
                        try
                        {
                            classSpecifier(sdw);
							flags.setEncounteredTypename(true);
                            break;
                        }
                        catch (Backtrack bt)
                        {
                            elaboratedTypeSpecifier(sdw);
                            flags.setEncounteredTypename(true);
                            break;
                        }
                    }
                    else
                    {
                        elaboratedTypeSpecifier(sdw);
                        flags.setEncounteredTypename(true);
                        break;
                    }
                case IToken.t_enum :
                    if (!parm || !forKR )
                    {
                        try
                        {
                            enumSpecifier(sdw);
							flags.setEncounteredTypename(true);
                            break;
                        }
                        catch (Backtrack bt)
                        {
                            // this is an elaborated class specifier
                            elaboratedTypeSpecifier(sdw);
                            flags.setEncounteredTypename(true);
                            break;
                        }
                    }
                    else
                    {
                        elaboratedTypeSpecifier(sdw);
                        flags.setEncounteredTypename(true);
                        break;
                    }
                default :
                    break declSpecifiers;
            }
        }
        if (typeNameBegin != null)
            sdw.setTypeName(new TokenDuple(typeNameBegin, typeNameEnd));
    }
    /**
     * Parse an elaborated type specifier.  
     * 
     * @param decl			Declaration which owns the elaborated type 
     * @throws Backtrack	request a backtrack
     */
    protected void elaboratedTypeSpecifier(DeclarationWrapper sdw)
        throws Backtrack
    {
        // this is an elaborated class specifier
        IToken t = consume();
        ASTClassKind eck = null;
        switch (t.getType())
        {
            case Token.t_class :
                eck = ASTClassKind.CLASS;
                break;
            case Token.t_struct :
                eck = ASTClassKind.STRUCT;
                break;
            case Token.t_union :
                eck = ASTClassKind.UNION;
                break;
            case Token.t_enum :
                eck = ASTClassKind.ENUM;
                break;
            default :
                break;
        }
 
        ITokenDuple d = name();
        IASTElaboratedTypeSpecifier elaboratedTypeSpec = null;
		final boolean isForewardDecl = ( LT(1) == IToken.tSEMI );
		
        try
        {
            elaboratedTypeSpec =
                astFactory.createElaboratedTypeSpecifier(
                    sdw.getScope(),
                    eck,
                    d,
                    t.getOffset(),
                    d.getLastToken().getEndOffset(), 
                    isForewardDecl );
        }
        catch (ASTSemanticException e)
        {
			failParse();
			throw backtrack;
        }
        sdw.setTypeSpecifier(elaboratedTypeSpec);
        
        if( isForewardDecl )
        	elaboratedTypeSpec.acceptElement( requestor );
    }
    /**
     * Consumes template parameters.  
     *
     * @param previousLast	Previous "last" token (returned if nothing was consumed)
     * @return				Last consumed token, or <code>previousLast</code> if nothing was consumed
     * @throws Backtrack	request a backtrack
     */
    private IToken consumeTemplateParameters(IToken previousLast)
        throws Backtrack
    {
        IToken last = previousLast;
        if (LT(1) == IToken.tLT)
        {
            last = consume(IToken.tLT);
            // until we get all the names sorted out
            Stack scopes = new Stack();
            scopes.push(new Integer(IToken.tLT));
            
            while (!scopes.empty())
            {
				int top;
                last = consume();
                
                switch (last.getType()) {
                    case IToken.tGT :
                        if (((Integer)scopes.peek()).intValue() == IToken.tLT) {
							scopes.pop();
						}
                        break;
					case IToken.tRBRACKET :
						do {
							top = ((Integer)scopes.pop()).intValue();
						} while (!scopes.empty() && (top == IToken.tGT || top == IToken.tLT));
						if (top != IToken.tLBRACKET) throw backtrack;
						
						break;
					case IToken.tRPAREN :
						do {
							top = ((Integer)scopes.pop()).intValue();
						} while (!scopes.empty() && (top == IToken.tGT || top == IToken.tLT));
						if (top != IToken.tLPAREN) throw backtrack;
							
						break;
                    case IToken.tLT :
					case IToken.tLBRACKET:
					case IToken.tLPAREN:
						scopes.push(new Integer(last.getType()));
                        break;
                }
            }
        }
        return last;
    }
    /**
     * Parse an identifier.  
     * 
     * @throws Backtrack	request a backtrack
     */
    protected IToken identifier() throws Backtrack
    {
        IToken first = consume(IToken.tIDENTIFIER); // throws backtrack if its not that
        return first;
    }
    /**
     * Parses a className.  
     * 
     * class-name: identifier | template-id
     * 
     * @throws Backtrack
     */
    protected ITokenDuple className() throws Backtrack
    {
		ITokenDuple duple = name();
		IToken last = duple.getLastToken(); 
        if (LT(1) == IToken.tLT) {
			last = consumeTemplateParameters(duple.getLastToken());
        }
        
		return new TokenDuple(duple.getFirstToken(), last);
    }
    
    /**
     * Parse a template-id, according to the ANSI C++ spec.  
     * 
     * template-id: template-name < template-argument-list opt >
     * template-name : identifier
     * 
     * @return		the last token that we consumed in a successful parse 
     * 
     * @throws Backtrack	request a backtrack
     */
    protected IToken templateId() throws Backtrack
    {
        ITokenDuple duple = name();
        IToken last = consumeTemplateParameters(duple.getLastToken());
        return last;
    }
    /**
     * Parse a name.
     * 
     * name
     * : ("::")? name2 ("::" name2)*
     * 
     * name2
     * : IDENTIFER
     * 
     * @throws Backtrack	request a backtrack
     */
    protected TokenDuple name() throws Backtrack
    {
        IToken first = LA(1);
        IToken last = null;
        IToken mark = mark();
 
        if (LT(1) == IToken.tCOLONCOLON)
            last = consume();
        // TODO - whacky way to deal with destructors, please revisit
        if (LT(1) == IToken.tCOMPL)
            consume();
        switch (LT(1))
        {
            case IToken.tIDENTIFIER :
                last = consume();
                last = consumeTemplateParameters(last);
                break;
            default :
                backup(mark);
                throw backtrack;
        }
        while (LT(1) == IToken.tCOLONCOLON)
        {
            last = consume();
            if (LT(1) == IToken.t_template)
                consume();
            if (LT(1) == IToken.tCOMPL)
                consume();
            switch (LT(1))
            {
                case IToken.t_operator :
                    backup(mark);
                    throw backtrack;
                case IToken.tIDENTIFIER :
                    last = consume();
                    last = consumeTemplateParameters(last);
            }
        }

        return new TokenDuple(first, last);
    }
    /**
     * Parse a const-volatile qualifier.  
     * 
     * cvQualifier
     * : "const" | "volatile"
     * 
     * TODO: fix this 
     * @param ptrOp		Pointer Operator that const-volatile applies to. 		  		
     * @return			Returns the same object sent in.
     * @throws Backtrack
     */
    protected boolean cvQualifier(
        Declarator declarator)
        throws Backtrack
    {
        switch (LT(1))
        {
            case IToken.t_const :
            	consume( IToken.t_const ); 
                declarator.addPtrOp(ASTPointerOperator.CONST_POINTER);
                return true;
            case IToken.t_volatile :
            	consume( IToken.t_volatile ); 
                declarator.addPtrOp(ASTPointerOperator.VOLATILE_POINTER);
                return true;
            default :
                return false;
        }
    }
    /**
     * Parses the initDeclarator construct of the ANSI C++ spec.
     * 
     * initDeclarator
     * : declarator ("=" initializerClause | "(" expressionList ")")?
     * @param owner			IParserCallback object that represents the owner declaration object.  
     * @return				declarator that this parsing produced.  
     * @throws Backtrack	request a backtrack
     */
    protected Declarator initDeclarator(
        DeclarationWrapper sdw, boolean forKR, SimpleDeclarationStrategy strategy )
        throws Backtrack
    {
        Declarator d = declarator(sdw, sdw.getScope(), forKR, strategy );
        // handle = initializerClause
        if (LT(1) == IToken.tASSIGN)
        {
            consume(IToken.tASSIGN);
            d.setInitializerClause(initializerClause(sdw.getScope()));
        }
        else if (LT(1) == IToken.tLPAREN)
        {
            // initializer in constructor
            consume(IToken.tLPAREN); // EAT IT!
            IASTExpression astExpression = null;
            astExpression = expression(sdw.getScope());
            consume(IToken.tRPAREN);
            d.setConstructorExpression(astExpression);
        }
        sdw.addDeclarator(d);
        return d;
    }
    /**
     * 
     */
    protected IASTInitializerClause initializerClause(IASTScope scope)
        throws Backtrack
    {
        if (LT(1) == IToken.tLBRACE)
        {
            //TODO - parse this for real
            consume(IToken.tLBRACE);
            if (LT(1) == (IToken.tRBRACE))
            {
                consume(IToken.tRBRACE);
                return astFactory.createInitializerClause(
                    IASTInitializerClause.Kind.EMPTY,
                    null,
                    null);
            }
            // otherwise it is a list of initializers
            List initializerClauses = new ArrayList();
            for (;;)
            {
                IASTInitializerClause clause = initializerClause(scope);
                initializerClauses.add(clause);
                if (LT(1) == IToken.tRBRACE)
                    break;
                consume(IToken.tCOMMA);
            }
            consume(IToken.tRBRACE);
            return astFactory.createInitializerClause(
                IASTInitializerClause.Kind.INITIALIZER_LIST,
                null,
                initializerClauses);
        }
        // try this now instead
        // assignmentExpression || { initializerList , } || { }
        try
        {
  
            IToken marked = mark();
            IASTExpression assignmentExpression =
                assignmentExpression(scope);
   
            return astFactory.createInitializerClause(
                IASTInitializerClause.Kind.ASSIGNMENT_EXPRESSION,
                assignmentExpression,
                null);
        }
        catch (Backtrack b)
        {
			// who cares
        }
        throw backtrack;
    }
    /**
     * Parse a declarator, as according to the ANSI C++ specification. 
     * 
     * declarator
     * : (ptrOperator)* directDeclarator
     * 
     * directDeclarator
     * : declaratorId
     * | directDeclarator "(" parameterDeclarationClause ")" (cvQualifier)*
     *     (exceptionSpecification)*
     * | directDeclarator "[" (constantExpression)? "]"
     * | "(" declarator")"
     * | directDeclarator "(" parameterDeclarationClause ")" (oldKRParameterDeclaration)*
     * 
     * declaratorId
     * : name
     * 
    	 * @param container		IParserCallback object that represents the owner declaration.  
     * @return				declarator that this parsing produced.
     * @throws Backtrack	request a backtrack
     */
    protected Declarator declarator(
        IDeclaratorOwner owner, IASTScope scope, boolean forKR, SimpleDeclarationStrategy strategy )
        throws Backtrack
    {
        Declarator d = null;
        DeclarationWrapper sdw = owner.getDeclarationWrapper();
        overallLoop : do
        {
            d = new Declarator(owner);
 
            consumePointerOperators(d, false);
 
            if (LT(1) == IToken.tLPAREN)
            {
                consume();
                declarator(d, scope, forKR, strategy );
                consume(IToken.tRPAREN);
            }
            else if (LT(1) == IToken.t_operator)
                operatorId(d, null);
            else
            {
                try
                {
                    ITokenDuple duple = name();
                    d.setName(duple);

                }
                catch (Backtrack bt)
                {
                    IToken start = null;
                    IToken mark = mark();
                    if (LT(1) == IToken.tCOLONCOLON
                        || LT(1) == IToken.tIDENTIFIER)
                    {
                        start = consume();
                        IToken end = null;
                        if (start.getType() == IToken.tIDENTIFIER)
                            end = consumeTemplateParameters(end);
                            while (LT(1) == IToken.tCOLONCOLON
                                || LT(1) == IToken.tIDENTIFIER)
                            {
                                end = consume();
                                if (end.getType() == IToken.tIDENTIFIER)
                                    end = consumeTemplateParameters(end);
                            }
                        if (LT(1) == IToken.t_operator)
                            operatorId(d, start);
                        else
                        {
                            backup(mark);
                            throw backtrack;
                        }
                    }
                }
            }
            for (;;)
            {
                switch (LT(1))
                {
                    case IToken.tLPAREN :
                    	if( forKR )
                    		throw backtrack;
                    
                        // temporary fix for initializer/function declaration ambiguity
                        if (!LA(2).looksLikeExpression() && strategy != SimpleDeclarationStrategy.TRY_VARIABLE  )
                        {
                            // parameterDeclarationClause
                            d.setIsFunction(true);
							// TODO need to create a temporary scope object here 
                            consume();
                            boolean seenParameter = false;
                            parameterDeclarationLoop : for (;;)
                            {
                                switch (LT(1))
                                {
                                    case IToken.tRPAREN :
                                        consume();
                                        break parameterDeclarationLoop;
                                    case IToken.tELIPSE :
                                        consume();
                                        break;
                                    case IToken.tCOMMA :
                                        consume();
                                        seenParameter = false;
                                        break;
                                    default :
                                        if (seenParameter)
                                            throw backtrack;
                                        parameterDeclaration(d, scope);
                                        seenParameter = true;
                                }
                            }

                            if (LT(1) == IToken.tCOLON)
                            {
                                break overallLoop;
                            }
                            IToken beforeCVModifier = mark();
                            IToken cvModifier = null;
                            IToken afterCVModifier = beforeCVModifier;
                            // const-volatile
                            // 2 options: either this is a marker for the method,
                            // or it might be the beginning of old K&R style parameter declaration, see
                            //      void getenv(name) const char * name; {}
                            // This will be determined further below
                            if (LT(1) == IToken.t_const
                                || LT(1) == IToken.t_volatile)
                            {
                                cvModifier = consume();
                                afterCVModifier = mark();
                            }
                            //check for throws clause here 
                            List exceptionSpecIds = null;
                            if (LT(1) == IToken.t_throw)
                            {
                                exceptionSpecIds = new ArrayList();
                                consume(); // throw
                                consume(IToken.tLPAREN); // (
                                boolean done = false;
                                ITokenDuple duple = null;
                                while (!done)
                                {
                                    switch (LT(1))
                                    {
                                        case IToken.tRPAREN :
                                            consume();
                                            done = true;
                                            break;
                                        case IToken.tCOMMA :
                                            consume();
                                            break;
                                        default :
                                            String image = LA(1).getImage();
                                            try
                                            {
                                                duple = typeId();
                                                exceptionSpecIds.add(duple);
                                            }
                                            catch (Backtrack e)
                                            {
                                                failParse();
                                                Util.debugLog(
                                                    "Unexpected Token ="
                                                        + image,IDebugLogConstants.PARSER);
                                                consume();
                                                // eat this token anyway
                                                continue;
                                            }
                                            break;
                                    }
                                }
                                if (exceptionSpecIds != null)
                                    try
                                    {
                                        d.setExceptionSpecification(
                                            astFactory
                                                .createExceptionSpecification(
                                                d.getDeclarationWrapper().getScope(), exceptionSpecIds));
                                    }
                                    catch (ASTSemanticException e)
                                    {
                                        failParse();
                                        throw backtrack;
                                    }
                            }
                            // check for optional pure virtual							
                            if (LT(1) == IToken.tASSIGN
                                && LT(2) == IToken.tINTEGER
                                && LA(2).getImage().equals("0"))
                            {
                                consume(IToken.tASSIGN);
                                consume(IToken.tINTEGER);
                                d.setPureVirtual(true);
                            }
                            if (afterCVModifier != LA(1)
                                || LT(1) == IToken.tSEMI)
                            {
                                // There were C++-specific clauses after const/volatile modifier
                                // Then it is a marker for the method
                                if (cvModifier != null)
                                {
               
                                    if (cvModifier.getType() == IToken.t_const)
                                        d.setConst(true);
                                    if (cvModifier.getType()
                                        == IToken.t_volatile)
                                        d.setVolatile(true);
                                }
                                afterCVModifier = mark();
                                // In this case (method) we can't expect K&R parameter declarations,
                                // but we'll check anyway, for errorhandling
                            }
                            else
                            {
                                // let's try this modifier as part of K&R parameter declaration
                                if (cvModifier != null)
                                    backup(beforeCVModifier);
                            }
                            if (LT(1) != IToken.tSEMI)
                            {
                                // try K&R-style parameter declarations
  
                                try
                                {
                                    do
                                    {
                                        simpleDeclaration(
                                            null,
                                            true,
                                            sdw.getScope(),
                                            sdw.getOwnerTemplate());
                                    }
                                    while (LT(1) != IToken.tLBRACE);
                                }
                                catch (Backtrack bt)
                                {
                                    // Something is wrong, 
                                    // this is not a proper K&R declaration clause
                                    backup(afterCVModifier);
                                }

                            }
                        }
                        break;
                    case IToken.tLBRACKET :
                        while (LT(1) == IToken.tLBRACKET)
                        {
                            consume(); // eat the '['
                          
                            IASTExpression exp = null;
                            if (LT(1) != IToken.tRBRACKET)
                            {
                                exp = constantExpression(sdw.getScope());
                            }
                            consume(IToken.tRBRACKET);
                            IASTArrayModifier arrayMod =
                                astFactory.createArrayModifier(exp);
                            d.addArrayModifier(arrayMod);

                        }
                        continue;
                    case IToken.tCOLON :
                        consume(IToken.tCOLON);
                        IASTExpression exp = null;
                        exp = constantExpression(scope);
                        d.setBitFieldExpression(exp);
                    default :
                        break;
                }
                break;
            }
            if (LA(1).getType() != IToken.tIDENTIFIER)
                break;

        }
        while (true);
        if (d.getOwner() instanceof Declarator)
             ((Declarator)d.getOwner()).setOwnedDeclarator(d);
        return d;
    }
    protected void operatorId(
        Declarator d,
        IToken originalToken)
        throws Backtrack, EndOfFile
    {
        // we know this is an operator
        IToken operatorToken = consume(IToken.t_operator);
        IToken toSend = null;
        if (LA(1).isOperator()
            || LT(1) == IToken.tLPAREN
            || LT(1) == IToken.tLBRACKET)
        {
            if ((LT(1) == IToken.t_new || LT(1) == IToken.t_delete)
                && LT(2) == IToken.tLBRACKET
                && LT(3) == IToken.tRBRACKET)
            {
                consume();
                consume(IToken.tLBRACKET);
                toSend = consume(IToken.tRBRACKET);
                // vector new and delete operators
            }
            else if (LT(1) == IToken.tLPAREN && LT(2) == IToken.tRPAREN)
            {
                // operator ()
                consume(IToken.tLPAREN);
                toSend = consume(IToken.tRPAREN);
            }
            else if (LT(1) == IToken.tLBRACKET && LT(2) == IToken.tRBRACKET)
            {
                consume(IToken.tLBRACKET);
                toSend = consume(IToken.tRBRACKET);
            }
            else if (LA(1).isOperator())
                toSend = consume();
            else
                throw backtrack;
        }
        else
        {
            // must be a conversion function
            typeId();
            toSend = lastToken;
            try
            {
                // this ptrOp doesn't belong to the declarator, 
                // it's just a part of the name
                consumePointerOperators(d, true);
                if( lastToken != null )
                	toSend = lastToken;
            }
            catch (Backtrack b)
            {
            }
            // In case we'll need better error recovery 
            // while( LT(1) != Token.tLPAREN )	{ toSend = consume(); }
        }
        ITokenDuple duple =
            new TokenDuple(
                originalToken == null ? operatorToken : originalToken,
                toSend);
   
        d.setName(duple);
    }
    /**
     * Parse a Pointer Operator.   
     * 
     * ptrOperator
     * : "*" (cvQualifier)*
     * | "&"
     * | ::? nestedNameSpecifier "*" (cvQualifier)*
     * 
     * @param owner 		Declarator that this pointer operator corresponds to.  
     * @throws Backtrack 	request a backtrack
     */
    protected void consumePointerOperators(Declarator d, boolean consumeOnlyOne) throws Backtrack
    {
    	for( ; ; )
    	{
	        int t = LT(1);
	        if (t == IToken.tAMPER)
	        {
	        	consume( IToken.tAMPER ); 
	            d.addPtrOp(ASTPointerOperator.REFERENCE);
	            if( consumeOnlyOne ) return;
	            continue;
	        }
	        IToken mark = mark();
	        IToken tokenType = LA(1);
	        ITokenDuple nameDuple = null;
	        if (t == IToken.tIDENTIFIER || t == IToken.tCOLONCOLON)
	        {
	        	try
	        	{
		            nameDuple = name();
	        	}
	        	catch( Backtrack bt )
	        	{
	        		backup( mark ); 
	        		return;
	        	}
	            t = LT(1);
	        }
	        if (t == IToken.tSTAR)
	        {
	            tokenType = consume(Token.tSTAR); // tokenType = "*"
	
				d.setPointerOperatorName(nameDuple);
	
				boolean successful = false;
	            for (;;)
	            {
                    boolean newSuccess = cvQualifier(d);
                    if( newSuccess ) successful = true; 
                    else break;
                    
	            }
	            
				if( !successful )
				{
					d.addPtrOp( ASTPointerOperator.POINTER );
				}
				if( consumeOnlyOne ) return;
				continue;	            
	        }
	        backup(mark);
	        return;
    	}
    }
    /**
     * Parse an enumeration specifier, as according to the ANSI specs in C & C++.  
     * 
     * enumSpecifier:
     * 		"enum" (name)? "{" (enumerator-list) "}"
     * enumerator-list:
     * 	enumerator-definition
     *	enumerator-list , enumerator-definition
     * enumerator-definition:
     * 	enumerator
     *  enumerator = constant-expression
     * enumerator: identifier 
     * 
     * @param	owner		IParserCallback object that represents the declaration that owns this type specifier. 
     * @throws	Backtrack	request a backtrack
     */
    protected void enumSpecifier(DeclarationWrapper sdw)
        throws Backtrack
    {
        IToken mark = mark();
        IToken identifier = null;
        consume( IToken.t_enum );
        if (LT(1) == IToken.tIDENTIFIER)
        {
            identifier = identifier();
        }
        if (LT(1) == IToken.tLBRACE)
        {
            IASTEnumerationSpecifier enumeration = null;
            try
            {
                enumeration = astFactory.createEnumerationSpecifier(
                        sdw.getScope(),
                        ((identifier == null) ? "" : identifier.getImage()),
                        mark.getOffset(), 
                        ((identifier == null)
                            ? mark.getOffset()
                            : identifier.getOffset()));
            }
            catch (ASTSemanticException e)
            {
				failParse();
				throw backtrack;               
            }
            consume(IToken.tLBRACE);
            while (LT(1) != IToken.tRBRACE)
            {
                IToken enumeratorIdentifier = null;
                if (LT(1) == IToken.tIDENTIFIER)
                {
                    enumeratorIdentifier = identifier();
                }
                else
                {
                    throw backtrack;
                }
                IASTExpression initialValue = null;
                if (LT(1) == IToken.tASSIGN)
                {
                    consume(IToken.tASSIGN);
                    initialValue = constantExpression(sdw.getScope());
                }
  
                if (LT(1) == IToken.tRBRACE)
                {
                    try
                    {
                        astFactory.addEnumerator(
                            enumeration,
                            enumeratorIdentifier.getImage(),
                            enumeratorIdentifier.getOffset(),
                            enumeratorIdentifier.getEndOffset(),
                            initialValue);
                    }
                    catch (ASTSemanticException e1)
                    {
						failParse();
						throw backtrack;                   
                    }
                    break;
                }
                if (LT(1) != IToken.tCOMMA)
                {
                    throw backtrack;
                }
                try
                {
                    astFactory.addEnumerator(
                        enumeration,
                        enumeratorIdentifier.getImage(),
                        enumeratorIdentifier.getOffset(),
                        enumeratorIdentifier.getEndOffset(),
                        initialValue);
                }
                catch (ASTSemanticException e1)
                {
					failParse();
					throw backtrack; 
                }
                consume(IToken.tCOMMA);
            }
            IToken t = consume(IToken.tRBRACE);
            enumeration.setEndingOffset(t.getEndOffset());
            enumeration.acceptElement( requestor );
            sdw.setTypeSpecifier(enumeration);
        }
        else
        {
            // enumSpecifierAbort
            backup(mark);
            throw backtrack;
        }
    }
    /**
     * Parse a class/struct/union definition. 
     * 
     * classSpecifier
     * : classKey name (baseClause)? "{" (memberSpecification)* "}"
     * 
     * @param	owner		IParserCallback object that represents the declaration that owns this classSpecifier
     * @throws	Backtrack	request a backtrack
     */
    protected void classSpecifier(DeclarationWrapper sdw)
        throws Backtrack
    {
        ClassNameType nameType = ClassNameType.IDENTIFIER;
        ASTClassKind classKind = null;
        ASTAccessVisibility access = ASTAccessVisibility.PUBLIC;
        IToken classKey = null;
        IToken mark = mark();
        // class key
        switch (LT(1))
        {
            case IToken.t_class :
                classKey = consume();
                classKind = ASTClassKind.CLASS;
                access = ASTAccessVisibility.PRIVATE;
                break;
            case IToken.t_struct :
                classKey = consume();
                classKind = ASTClassKind.STRUCT;
                break;
            case IToken.t_union :
                classKey = consume();
                classKind = ASTClassKind.UNION;
                break;
            default :
                throw backtrack;
        }

        ITokenDuple duple = null;
        // class name
        if (LT(1) == IToken.tIDENTIFIER)
            duple = className();
        if (duple != null && !duple.isIdentifier())
            nameType = ClassNameType.TEMPLATE;
        if (LT(1) != IToken.tCOLON && LT(1) != IToken.tLBRACE)
        {
            backup(mark);
            throw backtrack;
        }
        IASTClassSpecifier astClassSpecifier = null;
        
        try
        {
            astClassSpecifier = 
                astFactory
                    .createClassSpecifier(
                        sdw.getScope(),
                        duple, 
                        classKind,
                        nameType,
                        access,
                        classKey.getOffset(),
            			duple == null ?  classKey.getOffset() : duple.getFirstToken().getOffset());
        }
        catch (ASTSemanticException e)
        {
			failParse();
			throw backtrack;
        }
        sdw.setTypeSpecifier(astClassSpecifier);
        // base clause
        if (LT(1) == IToken.tCOLON)
        {
            baseSpecifier(astClassSpecifier);
        }
        if (LT(1) == IToken.tLBRACE)
        {
            consume(IToken.tLBRACE);
            astClassSpecifier.enterScope( requestor );
            memberDeclarationLoop : while (LT(1) != IToken.tRBRACE)
            {
                IToken checkToken = LA(1);
                switch (LT(1))
                {
                    case IToken.t_public :
						consume(); 
						consume(IToken.tCOLON);
						astClassSpecifier.setCurrentVisibility( ASTAccessVisibility.PUBLIC );
						break;                    
                    case IToken.t_protected :
						consume(); 
						consume(IToken.tCOLON);
					astClassSpecifier.setCurrentVisibility( ASTAccessVisibility.PROTECTED);
						break;

                    case IToken.t_private :
                    	consume(); 
                        consume(IToken.tCOLON);
						astClassSpecifier.setCurrentVisibility( ASTAccessVisibility.PRIVATE);
                        break;
                    case IToken.tRBRACE :
                        consume(IToken.tRBRACE);
                        break memberDeclarationLoop;
                    default :
                        try
                        {
                            declaration(astClassSpecifier, null);
                        }
                        catch (Backtrack bt)
                        {
                            failParse();
                            if (checkToken == LA(1))
                                errorHandling();
                        }
                }
                if (checkToken == LA(1))
                    errorHandling();
            }
            // consume the }
            IToken lt = consume(IToken.tRBRACE);
            astClassSpecifier.setEndingOffset(lt.getEndOffset());
            astClassSpecifier.exitScope( requestor );
        }
    }
    /**
     * Parse the subclass-baseclauses for a class specification.  
     * 
     * baseclause:	: basespecifierlist
     * basespecifierlist: 	basespecifier
     * 						basespecifierlist, basespecifier
     * basespecifier:	::? nestednamespecifier? classname
     * 					virtual accessspecifier? ::? nestednamespecifier? classname
     * 					accessspecifier virtual? ::? nestednamespecifier? classname
     * accessspecifier:	private | protected | public
     * @param classSpecOwner
     * @throws Backtrack
     */
    protected void baseSpecifier(
        IASTClassSpecifier astClassSpec)
        throws Backtrack
    {
        consume(IToken.tCOLON);
        boolean isVirtual = false;
        ASTAccessVisibility visibility = ASTAccessVisibility.PUBLIC;
        ITokenDuple nameDuple = null;
        baseSpecifierLoop : for (;;)
        {
            switch (LT(1))
            {
                case IToken.t_virtual :
                    consume(IToken.t_virtual);
                    isVirtual = true;
                    break;
                case IToken.t_public :
                	consume(); 
                    break;
                case IToken.t_protected :
					consume();
				    visibility = ASTAccessVisibility.PROTECTED;
                    break;
                case IToken.t_private :
                    visibility = ASTAccessVisibility.PRIVATE;
					consume();
           			break;
                case IToken.tCOLONCOLON :
                case IToken.tIDENTIFIER :
                    nameDuple = name();
                    break;
                case IToken.tCOMMA :
                    try
                    {
                        astFactory.addBaseSpecifier(
                            astClassSpec,
                            isVirtual,
                            visibility,
                            nameDuple );
                    }
                    catch (ASTSemanticException e)
                    {
						failParse();
						throw backtrack;
                    }
                    isVirtual = false;
                    visibility = ASTAccessVisibility.PUBLIC;
                    nameDuple = null;                        
                    consume();
                    continue baseSpecifierLoop;
                default :
                    break baseSpecifierLoop;
            }
        }

        try
        {
            astFactory.addBaseSpecifier(
                astClassSpec,
                isVirtual,
                visibility,
                nameDuple );
        }
        catch (ASTSemanticException e)
        {
			failParse();
			throw backtrack;
        }
    }
    /**
     * Parses a function body. 
     * 
     * @throws Backtrack	request a backtrack
     */
    protected void functionBody() throws Backtrack
    {
        compoundStatement();
    }
    /**
     * Parses a statement. 
     * 
     * @throws Backtrack	request a backtrack
     */
    protected void statement(IASTScope scope) throws Backtrack
    {
        
        switch (LT(1))
        {
            case IToken.t_case :
                consume();
                constantExpression(scope);
                consume(IToken.tCOLON);
                statement(null);
                return;
            case IToken.t_default :
                consume();
                consume(IToken.tCOLON);
                statement(null);
                return;
            case IToken.tLBRACE :
                compoundStatement();
                return;
            case IToken.t_if :
                consume();
                consume(IToken.tLPAREN);
                condition();
                consume(IToken.tRPAREN);
                statement(null);
                if (LT(1) == IToken.t_else)
                {
                    consume();
                    statement(null);
                }
                return;
            case IToken.t_switch :
                consume();
                consume(IToken.tLPAREN);
                condition();
                consume(IToken.tRPAREN);
                statement(null);
                return;
            case IToken.t_while :
                consume();
                consume(IToken.tLPAREN);
                condition();
                consume(IToken.tRPAREN);
                statement(null);
                return;
            case IToken.t_do :
                consume();
                statement(null);
                consume(IToken.t_while);
                consume(IToken.tLPAREN);
                condition();
                consume(IToken.tRPAREN);
                return;
            case IToken.t_for :
                consume();
                consume(IToken.tLPAREN);
                forInitStatement();
                if (LT(1) != IToken.tSEMI)
                    condition();
                consume(IToken.tSEMI);
                if (LT(1) != IToken.tRPAREN)
                {
                    //TODO get rid of NULL  
                    expression(scope);
                }
                consume(IToken.tRPAREN);
                statement(null);
                return;
            case IToken.t_break :
                consume();
                consume(IToken.tSEMI);
                return;
            case IToken.t_continue :
                consume();
                consume(IToken.tSEMI);
                return;
            case IToken.t_return :
                consume();
                if (LT(1) != IToken.tSEMI)
                {
                    //TODO get rid of NULL  
                    expression(scope);
                }
                consume(IToken.tSEMI);
                return;
            case IToken.t_goto :
                consume();
                consume(IToken.tIDENTIFIER);
                consume(IToken.tSEMI);
                return;
            case IToken.t_try :
                consume();
                compoundStatement();
                while (LT(1) == IToken.t_catch)
                {
                    consume();
                    consume(IToken.tLPAREN);
                    declaration(null, null); // was exceptionDeclaration
                    consume(IToken.tRPAREN);
                    compoundStatement();
                }
                return;
            case IToken.tSEMI :
                consume();
                return;
            default :
                // can be many things:
                // label
                if (LT(1) == IToken.tIDENTIFIER && LT(2) == IToken.tCOLON)
                {
                    consume();
                    consume();
                    statement(null);
                    return;
                }
                // expressionStatement
                // Note: the function style cast ambiguity is handled in expression
                // Since it only happens when we are in a statement
                try
                {
                    expression(scope);
                    consume(IToken.tSEMI);
                    return;
                }
                catch (Backtrack b)
                {
                }
                // declarationStatement
                declaration(null, null);
        }
    }
    /**
     * @throws Backtrack
     */
    protected void condition() throws Backtrack
    {
        // TO DO
    }
    /**
     * @throws Backtrack
     */
    protected void forInitStatement() throws Backtrack
    {
        // TO DO
    }
    /**
     * @throws Backtrack
     */
    protected void compoundStatement() throws Backtrack
    {
        consume(IToken.tLBRACE);
        while (LT(1) != IToken.tRBRACE)
            statement(null);
        consume();
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression constantExpression( IASTScope scope )
        throws Backtrack
    {
        return conditionalExpression(scope);
    }
    /* (non-Javadoc)
     * @see org.eclipse.cdt.internal.core.parser.IParser#expression(java.lang.Object)
     */
    public IASTExpression expression(IASTScope scope) throws Backtrack
    {
        IASTExpression assignmentExpression = assignmentExpression(scope);
        while (LT(1) == IToken.tCOMMA)
        {
            IToken t = consume();
            IASTExpression secondExpression = assignmentExpression(scope);
            try
            {
                assignmentExpression =
                    astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.EXPRESSIONLIST,
                        assignmentExpression,
                        secondExpression,
                        null,
                        null,
                        null,
                        "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        return assignmentExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression assignmentExpression( IASTScope scope )
        throws Backtrack
    {
        if (LT(1) == IToken.t_throw)
        {
            return throwExpression(scope);
        }
        IASTExpression conditionalExpression =
            conditionalExpression(scope);
        // if the condition not taken, try assignment operators
        if (conditionalExpression != null
            && conditionalExpression.getExpressionKind()
                == IASTExpression.Kind.CONDITIONALEXPRESSION_HARD)
            return conditionalExpression;
        switch (LT(1))
        {
            case IToken.tASSIGN :
                return assignmentOperatorExpression( scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_NORMAL);
            case IToken.tSTARASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_MULT);
            case IToken.tDIVASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_DIV);
            case IToken.tMODASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_MOD);
            case IToken.tPLUSASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_PLUS);
            case IToken.tMINUSASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_MINUS);
            case IToken.tSHIFTRASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_RSHIFT);
            case IToken.tSHIFTLASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_LSHIFT);
            case IToken.tAMPERASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_AND);
            case IToken.tXORASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_XOR);
            case IToken.tBITORASSIGN :
                return assignmentOperatorExpression(scope,
                    IASTExpression.Kind.ASSIGNMENTEXPRESSION_OR);
        }
        return conditionalExpression;
    }
    protected IASTExpression assignmentOperatorExpression(
    	IASTScope scope,
        IASTExpression.Kind kind)
        throws EndOfFile, Backtrack
    {
        IToken t = consume();
        IASTExpression assignmentExpression = assignmentExpression(scope);
 
        try
        {
            return astFactory.createExpression(
                scope,
                kind,
                assignmentExpression,
                null,
                null,
                null,
                null,
                "", null);
        }
        catch (ASTSemanticException e)
        {
            failParse();
            throw backtrack;
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression throwExpression( IASTScope scope )
        throws Backtrack
    {
        consume(IToken.t_throw);
        IASTExpression throwExpression = null;
        try
        {
            throwExpression = expression(scope);
        }
        catch (Backtrack b)
        {
        }
        try
        {
            return astFactory.createExpression(
                scope,
                IASTExpression.Kind.THROWEXPRESSION,
                throwExpression,
                null,
                null,
                null,
                null,
                "", null);
        }
        catch (ASTSemanticException e)
        {
            failParse();
            throw backtrack;
        }
    }
    /**
     * @param expression
     * @return
     * @throws Backtrack
     */
    protected IASTExpression conditionalExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = logicalOrExpression(scope);
        if (LT(1) == IToken.tQUESTION)
        {
            consume();
            IASTExpression secondExpression = expression(scope);
            consume(IToken.tCOLON);
            IASTExpression thirdExpression = assignmentExpression(scope);
            try
            {
                return astFactory.createExpression(
                    scope,
                    IASTExpression.Kind.CONDITIONALEXPRESSION_HARD,
                    firstExpression,
                    secondExpression,
                    thirdExpression,
                    null,
                    null,
                    "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        else
            return firstExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression logicalOrExpression(IASTScope scope)
        throws Backtrack
    {
        IASTExpression firstExpression = logicalAndExpression(scope);
        while (LT(1) == IToken.tOR)
        {
            IToken t = consume();
            IASTExpression secondExpression = logicalAndExpression(scope);

            try
            {
                firstExpression =
                    astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.LOGICALOREXPRESSION,
                        firstExpression,
                        secondExpression,
                        null,
                        null,
                        null,
                        "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        return firstExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression logicalAndExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = inclusiveOrExpression( scope );
        while (LT(1) == IToken.tAND)
        {
            IToken t = consume();
            IASTExpression secondExpression = inclusiveOrExpression( scope );
            try
            {
                firstExpression =
                    astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.LOGICALANDEXPRESSION,
                        firstExpression,
                        secondExpression,
                        null,
                        null,
                        null,
                        "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        return firstExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression inclusiveOrExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = exclusiveOrExpression(scope);
        while (LT(1) == IToken.tBITOR)
        {
            IToken t = consume();
            IASTExpression secondExpression = exclusiveOrExpression(scope);
  
            try
            {
                firstExpression =
                    astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.INCLUSIVEOREXPRESSION,
                        firstExpression,
                        secondExpression,
                        null,
                        null,
                        null,
                        "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        return firstExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression exclusiveOrExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = andExpression( scope );
        while (LT(1) == IToken.tXOR)
        {
            IToken t = consume();
            IASTExpression secondExpression = andExpression( scope );

            try
            {
                firstExpression =
                    astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.EXCLUSIVEOREXPRESSION,
                        firstExpression,
                        secondExpression,
                        null,
                        null,
                        null,
                        "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        return firstExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression andExpression(IASTScope scope) throws Backtrack
    {
        IASTExpression firstExpression = equalityExpression(scope);
        while (LT(1) == IToken.tAMPER)
        {
            IToken t = consume();
            IASTExpression secondExpression = equalityExpression(scope);
 
            try
            {
                firstExpression =
                    astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.ANDEXPRESSION,
                        firstExpression,
                        secondExpression,
                        null,
                        null,
                        null,
                        "", null);
            }
            catch (ASTSemanticException e)
            {
                failParse();
                throw backtrack;
            }
        }
        return firstExpression;
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression equalityExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = relationalExpression(scope);
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tEQUAL :
                case IToken.tNOTEQUAL :
                    IToken t = consume();
                    IASTExpression secondExpression =
                        relationalExpression(scope);

                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                (t.getType() == IToken.tEQUAL)
                                    ? IASTExpression.Kind.EQUALITY_EQUALS
                                    : IASTExpression.Kind.EQUALITY_NOTEQUALS,
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression relationalExpression(IASTScope scope)
        throws Backtrack
    {
        IASTExpression firstExpression = shiftExpression(scope);
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tGT :
                case IToken.tLT :
                case IToken.tLTEQUAL :
                case IToken.tGTEQUAL :
                    IToken mark = mark();
                    IToken t = consume();
                    IToken next = LA(1);
                    IASTExpression secondExpression =
                        shiftExpression(scope);
                    if (next == LA(1))
                    {
                        // we did not consume anything
                        // this is most likely an error
                        backup(mark);
                        return firstExpression;
                    }
                    else
                    {
                        IASTExpression.Kind kind = null;
                        switch (t.getType())
                        {
                            case IToken.tGT :
                                kind =
                                    IASTExpression.Kind.RELATIONAL_GREATERTHAN;
                                break;
                            case IToken.tLT :
                                kind = IASTExpression.Kind.RELATIONAL_LESSTHAN;
                                break;
                            case IToken.tLTEQUAL :
                                kind =
                                    IASTExpression
                                        .Kind
                                        .RELATIONAL_LESSTHANEQUALTO;
                                break;
                            case IToken.tGTEQUAL :
                                kind =
                                    IASTExpression
                                        .Kind
                                        .RELATIONAL_GREATERTHANEQUALTO;
                                break;
                        }
                        try
                        {
                            firstExpression =
                                astFactory.createExpression(
                                    scope,
                                    kind,
                                    firstExpression,
                                    secondExpression,
                                    null,
                                    null,
                                    null,
                                    "", null);
                        }
                        catch (ASTSemanticException e)
                        {
                            failParse();
                            throw backtrack;
                        }
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression shiftExpression(IASTScope scope)
        throws Backtrack
    {
        IASTExpression firstExpression = additiveExpression(scope);
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tSHIFTL :
                case IToken.tSHIFTR :
                    IToken t = consume();
                    IASTExpression secondExpression =
                        additiveExpression(scope);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                ((t.getType() == IToken.tSHIFTL)
                                    ? IASTExpression.Kind.SHIFT_LEFT
                                    : IASTExpression.Kind.SHIFT_RIGHT),
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression additiveExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = multiplicativeExpression( scope );
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tPLUS :
                case IToken.tMINUS :
                    IToken t = consume();
                    IASTExpression secondExpression =
                        multiplicativeExpression(scope);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                ((t.getType() == IToken.tPLUS)
                                    ? IASTExpression.Kind.ADDITIVE_PLUS
                                    : IASTExpression.Kind.ADDITIVE_MINUS),
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression multiplicativeExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = pmExpression(scope);
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tSTAR :
                case IToken.tDIV :
                case IToken.tMOD :
                    IToken t = consume();
                    IASTExpression secondExpression = pmExpression(scope);
                    IASTExpression.Kind kind = null;
                    switch (t.getType())
                    {
                        case IToken.tSTAR :
                            kind = IASTExpression.Kind.MULTIPLICATIVE_MULTIPLY;
                            break;
                        case IToken.tDIV :
                            kind = IASTExpression.Kind.MULTIPLICATIVE_DIVIDE;
                            break;
                        case IToken.tMOD :
                            kind = IASTExpression.Kind.MULTIPLICATIVE_MODULUS;
                            break;
                    }
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                kind,
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression pmExpression( IASTScope scope ) throws Backtrack
    {
        IASTExpression firstExpression = castExpression(scope);
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tDOTSTAR :
                case IToken.tARROWSTAR :
                    IToken t = consume();
                    IASTExpression secondExpression =
                        castExpression(scope);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                ((t.getType() == IToken.tDOTSTAR)
                                    ? IASTExpression.Kind.PM_DOTSTAR
                                    : IASTExpression.Kind.PM_ARROWSTAR),
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    /**
     * castExpression
     * : unaryExpression
     * | "(" typeId ")" castExpression
     */
    protected IASTExpression castExpression( IASTScope scope ) throws Backtrack
    {
        // TO DO: we need proper symbol checkint to ensure type name
        if (LT(1) == IToken.tLPAREN)
        {
            IToken mark = mark();
            consume();
            ITokenDuple duple = null;
            // If this isn't a type name, then we shouldn't be here
            try
            {
                if (LT(1) == IToken.t_const)
                    consume();
                duple = typeId();
                while (LT(1) == IToken.tSTAR)
                {
                    consume(IToken.tSTAR);
                    if (LT(1) == IToken.t_const || LT(1) == IToken.t_volatile)
                        consume();
                }
                consume(IToken.tRPAREN);
                IASTExpression castExpression = castExpression(scope);
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.CASTEXPRESSION,
                        castExpression,
                        null,
                        null,
                        null,
                        duple,
                        "", null);
                }
                catch (ASTSemanticException e)
                {
                    failParse();
                    throw backtrack;
                }
            }
            catch (Backtrack b)
            {
                backup(mark);
            }
        }
        return unaryExpression(scope);
    }
    /**
     * @throws Backtrack
     */
    protected ITokenDuple typeId() throws Backtrack
    {
        IToken begin = LA(1);
        IToken end = null;
        try
        {
            ITokenDuple d = name();
            return d;
        }
        catch (Backtrack b)
        {
            simpleMods : for (;;)
            {
                switch (LT(1))
                {
					 case IToken.t_signed :
					 case IToken.t_unsigned :
					 case IToken.t_short :
					 case IToken.t_long :
					 case IToken.t_const :
					 case IToken.t_volatile :
                        end = consume();
                        break;
                    case IToken.tAMPER :
                    case IToken.tSTAR :
                    case IToken.tIDENTIFIER :
                        if (end == null)
                            throw backtrack;
                        end = consume();
                        break;
                    case IToken.t_int :
                    case IToken.t_char :
                    case IToken.t_bool :
                    case IToken.t_double :
                    case IToken.t_float :
                    case IToken.t_wchar_t :
                    case IToken.t_void :
                        end = consume();
                    default :
                        break simpleMods;
                }
            }
            if (end != null)
            {
                return new TokenDuple(begin, end);
            }
            else if (
                LT(1) == IToken.t_typename
                    || LT(1) == IToken.t_struct
                    || LT(1) == IToken.t_class
                    || LT(1) == IToken.t_enum
                    || LT(1) == IToken.t_union)
            {
                consume();
                ITokenDuple d = name();
                return new TokenDuple(begin, d.getLastToken());
            }
            else
                throw backtrack;
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression deleteExpression( IASTScope scope )
        throws Backtrack
    {
        if (LT(1) == IToken.tCOLONCOLON)
        {
            // global scope
            consume();
        }
        consume(IToken.t_delete);
        boolean vectored = false;
        if (LT(1) == IToken.tLBRACKET)
        {
            // array delete
            consume();
            consume(IToken.tRBRACKET);
            vectored = true;
        }
        IASTExpression castExpression = castExpression(scope);
        try
        {
            return astFactory.createExpression(
                scope,
                (vectored
                    ? IASTExpression.Kind.DELETE_VECTORCASTEXPRESSION
                    : IASTExpression.Kind.DELETE_CASTEXPRESSION),
                castExpression,
                null,
                null,
                null,
                null,
                "", null);
        }
        catch (ASTSemanticException e)
        {
            failParse();
            throw backtrack;
        }
    }
    /**
     * Pazse a new-expression.  
     * 
     * @param expression
     * @throws Backtrack
     * 
     * 
     * newexpression: 	::? new newplacement? newtypeid newinitializer?
     *					::? new newplacement? ( typeid ) newinitializer?
     * newplacement:	( expressionlist )
     * newtypeid:		typespecifierseq newdeclarator?
     * newdeclarator:	ptroperator newdeclarator? | directnewdeclarator
     * directnewdeclarator:		[ expression ]
     *							directnewdeclarator [ constantexpression ]
     * newinitializer:	( expressionlist? )
     */
    protected IASTExpression newExpression( IASTScope scope ) throws Backtrack
    {
        if (LT(1) == IToken.tCOLONCOLON)
        {
            // global scope
            consume();
        }
        consume(IToken.t_new);
        boolean typeIdInParen = false;
        boolean placementParseFailure = true;
        IToken beforeSecondParen = null;
        IToken backtrackMarker = null;
        ITokenDuple typeId = null;
		ArrayList newPlacementExpressions = new ArrayList();
		ArrayList newTypeIdExpressions = new ArrayList();
		ArrayList newInitializerExpressions = new ArrayList();
				
        if (LT(1) == IToken.tLPAREN)
        {
            consume(IToken.tLPAREN);
            try
            {
                // Try to consume placement list
                // Note: since expressionList and expression are the same...
                backtrackMarker = mark();
				newPlacementExpressions.add(expression(scope));
                consume(IToken.tRPAREN);
                placementParseFailure = false;
                if (LT(1) == IToken.tLPAREN)
                {
                    beforeSecondParen = mark();
                    consume(IToken.tLPAREN);
                    typeIdInParen = true;
                }
            }
            catch (Backtrack e)
            {
                backup(backtrackMarker);
            }
            if (placementParseFailure)
            {
                // CASE: new (typeid-not-looking-as-placement) ...
                // the first expression in () is not a placement
                // - then it has to be typeId
                typeId = typeId();
                consume(IToken.tRPAREN);
            }
            else
            {
                if (!typeIdInParen)
                {
                    if (LT(1) == IToken.tLBRACKET)
                    {
                        // CASE: new (typeid-looking-as-placement) [expr]...
                        // the first expression in () has been parsed as a placement;
                        // however, we assume that it was in fact typeId, and this 
                        // new statement creates an array.
                        // Do nothing, fallback to array/initializer processing
                    }
                    else
                    {
                        // CASE: new (placement) typeid ...
                        // the first expression in () is parsed as a placement,
                        // and the next expression doesn't start with '(' or '['
                        // - then it has to be typeId
                        try
                        {
                            backtrackMarker = mark();
                            typeId = typeId();
                        }
                        catch (Backtrack e)
                        {
                            // Hmmm, so it wasn't typeId after all... Then it is
                            // CASE: new (typeid-looking-as-placement)
                            backup(backtrackMarker);
							// TODO fix this
                            return null; 
                        }
                    }
                }
                else
                {
                    // Tricky cases: first expression in () is parsed as a placement,
                    // and the next expression starts with '('.
                    // The problem is, the first expression might as well be a typeid
                    try
                    {
                        typeId = typeId();
                        consume(IToken.tRPAREN);
                        if (LT(1) == IToken.tLPAREN
                            || LT(1) == IToken.tLBRACKET)
                        {
                            // CASE: new (placement)(typeid)(initializer)
                            // CASE: new (placement)(typeid)[] ...
                            // Great, so far all our assumptions have been correct
                            // Do nothing, fallback to array/initializer processing
                        }
                        else
                        {
                            // CASE: new (placement)(typeid)
                            // CASE: new (typeid-looking-as-placement)(initializer-looking-as-typeid)
                            // Worst-case scenario - this cannot be resolved w/o more semantic information.
                            // Luckily, we don't need to know what was that - we only know that 
                            // new-expression ends here.
							try
							{
							return astFactory.createExpression(
								scope, IASTExpression.Kind.NEW_TYPEID, 
								null, null,	null, null, typeId, "", 
								astFactory.createNewDescriptor(newPlacementExpressions, newTypeIdExpressions, newInitializerExpressions));
							}
							catch (ASTSemanticException e)
							{
								failParse();
								return null;
							}
                        }
                    }
                    catch (Backtrack e)
                    {
                        // CASE: new (typeid-looking-as-placement)(initializer-not-looking-as-typeid)
                        // Fallback to initializer processing
                        backup(beforeSecondParen);
                    }
                }
            }
        }
        else
        {
            // CASE: new typeid ...
            // new parameters do not start with '('
            // i.e it has to be a plain typeId
            typeId = typeId();
        }
        while (LT(1) == IToken.tLBRACKET)
        {
            // array new
            consume();
			newTypeIdExpressions.add(assignmentExpression(scope));
            consume(IToken.tRBRACKET);
        }
        // newinitializer
        if (LT(1) == IToken.tLPAREN)
        {
            consume(IToken.tLPAREN);
            if (LT(1) != IToken.tRPAREN)
			newInitializerExpressions.add(expression(scope));
            consume(IToken.tRPAREN);
        }
		try
		{
        return astFactory.createExpression(
        	scope, IASTExpression.Kind.NEW_TYPEID, 
			null, null,	null, null, typeId, "", 
			astFactory.createNewDescriptor(newPlacementExpressions, newTypeIdExpressions, newInitializerExpressions));
		}
		catch (ASTSemanticException e)
		{
			failParse();
			return null;
		}
    }
    protected IASTExpression unaryOperatorCastExpression( IASTScope scope,
        IASTExpression.Kind kind,
        IToken consumed)
        throws Backtrack
    {
        IASTExpression castExpression = castExpression(scope);
        try
        {
            return astFactory.createExpression(
                scope,
                kind,
                castExpression,
                null,
                null,
                null,
                null,
                "", null);
        }
        catch (ASTSemanticException e)
        {
            failParse();
            throw backtrack;
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression unaryExpression( IASTScope scope )
        throws Backtrack
    {
        switch (LT(1))
        {
            case IToken.tSTAR :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_STAR_CASTEXPRESSION,
                    consume());
            case IToken.tAMPER :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_AMPSND_CASTEXPRESSION,
                    consume());
            case IToken.tPLUS :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_PLUS_CASTEXPRESSION,
                    consume());
            case IToken.tMINUS :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_MINUS_CASTEXPRESSION,
                    consume());
            case IToken.tNOT :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_NOT_CASTEXPRESSION,
                    consume());
            case IToken.tCOMPL :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_TILDE_CASTEXPRESSION,
                    consume());
            case IToken.tINCR :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_INCREMENT,
                    consume());
            case IToken.tDECR :
                return unaryOperatorCastExpression(scope,
                    IASTExpression.Kind.UNARY_DECREMENT,
                    consume());
            case IToken.t_sizeof :
                consume(IToken.t_sizeof);
                IToken mark = LA(1);
                ITokenDuple d = null;
                IASTExpression unaryExpression = null;
                if (LT(1) == IToken.tLPAREN)
                {
                    try
                    {
                        consume(IToken.tLPAREN);
                        d = typeId();
                        consume(IToken.tRPAREN);
                    }
                    catch (Backtrack bt)
                    {
                        backup(mark);
                        unaryExpression = unaryExpression(scope);
                    }
                }
                else
                {
                    unaryExpression = unaryExpression(scope);
                }
                if (d != null & unaryExpression == null)
                    try
                    {
                        return astFactory.createExpression(
                            scope,
                            IASTExpression.Kind.UNARY_SIZEOF_TYPEID,
                            null,
                            null,
                            null,
                            null,
                            d,
                            "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                else if (unaryExpression != null && d == null)
                    try
                    {
                        return astFactory.createExpression(
                            scope,
                            IASTExpression.Kind.UNARY_SIZEOF_UNARYEXPRESSION,
                            unaryExpression,
                            null,
                            null,
                            null,
                            null,
                            "", null);
                    }
                    catch (ASTSemanticException e1)
                    {
                        failParse();
                        throw backtrack;
                    }
                else
                    throw backtrack;
            case IToken.t_new :
                return newExpression(scope);
            case IToken.t_delete :
                return deleteExpression(scope);
            case IToken.tCOLONCOLON :
                switch (LT(2))
                {
                    case IToken.t_new :
                        return newExpression(scope);
                    case IToken.t_delete :
                        return deleteExpression(scope);
                    default :
                        return postfixExpression(scope);
                }
            default :
                return postfixExpression(scope);
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression postfixExpression( IASTScope scope )
        throws Backtrack
    {
        IASTExpression firstExpression = null;
        boolean isTemplate = false;
        switch (LT(1))
        {
            case IToken.t_typename :
                consume(); //TODO: the rest of this 
                break;
                // simple-type-specifier ( assignment-expression , .. )
            case IToken.t_char :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_CHAR);
                break;
            case IToken.t_wchar_t :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_WCHART);
                break;
            case IToken.t_bool :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_BOOL);
                break;
            case IToken.t_short :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_SHORT);
                break;
            case IToken.t_int :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_INT);
                break;
            case IToken.t_long :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_LONG);
                break;
            case IToken.t_signed :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_SIGNED);
                break;
            case IToken.t_unsigned :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_UNSIGNED);
                break;
            case IToken.t_float :
                firstExpression =
                    simpleTypeConstructorExpression(scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_FLOAT);
                break;
            case IToken.t_double :
                firstExpression =
                    simpleTypeConstructorExpression( scope,
                        IASTExpression.Kind.POSTFIX_SIMPLETYPE_DOUBLE);
                break;
            case IToken.t_dynamic_cast :
                firstExpression =
                    specialCastExpression(scope,
                        IASTExpression.Kind.POSTFIX_DYNAMIC_CAST);
                break;
            case IToken.t_static_cast :
                firstExpression =
                    specialCastExpression(scope,
                        IASTExpression.Kind.POSTFIX_STATIC_CAST);
                break;
            case IToken.t_reinterpret_cast :
                firstExpression =
                    specialCastExpression(scope,
                        IASTExpression.Kind.POSTFIX_REINTERPRET_CAST);
                break;
            case IToken.t_const_cast :
                firstExpression =
                    specialCastExpression(scope,
                        IASTExpression.Kind.POSTFIX_CONST_CAST);
                break;
            case IToken.t_typeid :
                consume();
                consume(IToken.tLPAREN);
                boolean isTypeId = true;
                IASTExpression lhs = null;
                ITokenDuple typeId = null;
                try
                {
                    typeId = typeId();
                }
                catch (Backtrack b)
                {
                    isTypeId = false;
                    lhs = expression(scope);
                }
                consume(IToken.tRPAREN);
                try
                {
                    firstExpression =
                        astFactory.createExpression(
                            scope,
                            (isTypeId
                                ? IASTExpression.Kind.POSTFIX_TYPEID_TYPEID
                                : IASTExpression.Kind.POSTFIX_TYPEID_EXPRESSION),
                            lhs,
                            null,
                            null,
                            null,
                            typeId,
                            "", null);
                }
                catch (ASTSemanticException e6)
                {
                    failParse();
                    throw backtrack;
                }
                break;
            default :
                firstExpression = primaryExpression(scope);
        }
        IASTExpression secondExpression = null;
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tLBRACKET :
                    // array access
                    consume();
                    secondExpression = expression(scope);
                    consume(IToken.tRBRACKET);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                IASTExpression.Kind.POSTFIX_SUBSCRIPT,
                                firstExpression,
                                secondExpression,
                                null,
                        		null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e2)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                case IToken.tLPAREN :
                    // function call
                    consume();
                    secondExpression = expression(scope);
                    consume(IToken.tRPAREN);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                IASTExpression.Kind.POSTFIX_FUNCTIONCALL,
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e3)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                case IToken.tINCR :
                    consume();
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                IASTExpression.Kind.POSTFIX_INCREMENT,
                                firstExpression,
                                null,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e1)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                case IToken.tDECR :
                    consume();
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                IASTExpression.Kind.POSTFIX_DECREMENT,
                                firstExpression,
                                null,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e4)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                case IToken.tDOT :
                    // member access
                    consume(IToken.tDOT);
                    if (LT(1) == IToken.t_template)
                    {
                        consume(IToken.t_template);
                        isTemplate = true;
                    }
                    secondExpression = primaryExpression(scope);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                (isTemplate
                                    ? IASTExpression.Kind.POSTFIX_DOT_TEMPL_IDEXPRESS
                                    : IASTExpression.Kind.POSTFIX_DOT_IDEXPRESSION),
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e5)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                case IToken.tARROW :
                    // member access
                    consume(IToken.tARROW);
                    if (LT(1) == IToken.t_template)
                    {
                        consume(IToken.t_template);
                        isTemplate = true;
                    }
                    secondExpression = primaryExpression(scope);
                    try
                    {
                        firstExpression =
                            astFactory.createExpression(
                                scope,
                                (isTemplate
                                    ? IASTExpression.Kind.POSTFIX_ARROW_TEMPL_IDEXP
                                    : IASTExpression.Kind.POSTFIX_ARROW_IDEXPRESSION),
                                firstExpression,
                                secondExpression,
                                null,
                                null,
                                null,
                                "", null);
                    }
                    catch (ASTSemanticException e)
                    {
                        failParse();
                        throw backtrack;
                    }
                    break;
                default :
                    return firstExpression;
            }
        }
    }
    protected IASTExpression specialCastExpression( IASTScope scope,
        IASTExpression.Kind kind)
        throws EndOfFile, Backtrack
    {
        consume();
        consume(IToken.tLT);
        ITokenDuple duple = typeId();
        consume(IToken.tGT);
        consume(IToken.tLPAREN);
        IASTExpression lhs = expression(scope);
        consume(IToken.tRPAREN);
        try
        {
            return astFactory.createExpression(
                scope,
                kind,
                lhs,
                null,
                null,
                null,
                null,
                "", null);
        }
        catch (ASTSemanticException e)
        {
            failParse();
            throw backtrack;
        }
    }
    protected IASTExpression simpleTypeConstructorExpression( IASTScope scope,
        Kind type)
        throws EndOfFile, Backtrack
    {
        consume();
        consume(IToken.tLPAREN);
        IASTExpression inside = expression(scope);
        consume(IToken.tRPAREN);
        try
        {
            return astFactory.createExpression(
                scope,
                type,
                inside,
                null,
                null,
                null,
                null,
                "", null);
        }
        catch (ASTSemanticException e)
        {
            failParse();
            throw backtrack;
        }
    }
    /**
     * @param expression
     * @throws Backtrack
     */
    protected IASTExpression primaryExpression( IASTScope scope )
        throws Backtrack
    {
        IToken t = null;
        switch (LT(1))
        {
            // TO DO: we need more literals...
            case IToken.tINTEGER :
                t = consume();
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_INTEGER_LITERAL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        t.getImage(), null);
                }
                catch (ASTSemanticException e1)
                {
                    failParse();
                    throw backtrack;
                }
            case IToken.tFLOATINGPT :
                t = consume();
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_FLOAT_LITERAL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        t.getImage(), null);
                }
                catch (ASTSemanticException e2)
                {
                    failParse();
                    throw backtrack;
                }
            case IToken.tSTRING :
            case IToken.tLSTRING :
				t = consume();
				try
                {
                    return astFactory.createExpression( scope, IASTExpression.Kind.PRIMARY_STRING_LITERAL, null, null, null, null, null, t.getImage(), null );
                }
                catch (ASTSemanticException e5)
                {
                    failParse();
                    throw backtrack;
                }
            
            case IToken.t_false :
            case IToken.t_true :
                t = consume();
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_BOOLEAN_LITERAL,
                        null,
                        null,
                        null,
                    	null,
                        null,
                        t.getImage(), null);
                }
                catch (ASTSemanticException e3)
                {
                    failParse();
                    throw backtrack;
                }
                  
            case IToken.tCHAR :
			case IToken.tLCHAR :

                t = consume();
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_CHAR_LITERAL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        t.getImage(), null);
                }
                catch (ASTSemanticException e4)
                {
                    failParse();
                    throw backtrack;
                }
                    
            case IToken.t_this :
                consume(IToken.t_this);
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_THIS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "", null);
                }
                catch (ASTSemanticException e7)
                {
                    failParse();
                    throw backtrack;
                }
            case IToken.tLPAREN :
                consume();
                IASTExpression lhs = expression(scope);
                consume(IToken.tRPAREN);
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_BRACKETED_EXPRESSION,
                        lhs,
                        null,
                        null,
                    	null,
                        null,
                        "", null);
                }
                catch (ASTSemanticException e6)
                {
                    failParse();
                    throw backtrack;
                }
            case IToken.tIDENTIFIER :
                ITokenDuple duple = name();
                //TODO should be an ID Expression really
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.ID_EXPRESSION,
                        null,
                        null,
                        null,
                    	null,
						duple,
                        "", null);
                }
                catch (ASTSemanticException e8)
                {
                    failParse();
                    throw backtrack;
                }
            default :
                try
                {
                    return astFactory.createExpression(
                        scope,
                        IASTExpression.Kind.PRIMARY_EMPTY,
                        null,
                        null,
                        null,
                    	null,
                        null,
                        "", null);
                }
                catch (ASTSemanticException e)
                {
                    failParse();
                    throw backtrack;
                }
        }
    }
    /**
     * @throws Exception
     */
    protected void varName() throws Exception
    {
        if (LT(1) == IToken.tCOLONCOLON)
            consume();
        for (;;)
        {
            switch (LT(1))
            {
                case IToken.tIDENTIFIER :
                    consume();
                    //if (isTemplateArgs()) {
                    //	rTemplateArgs();
                    //}
                    if (LT(1) == IToken.tCOLONCOLON)
                    {
                        switch (LT(2))
                        {
                            case IToken.tIDENTIFIER :
                            case IToken.tCOMPL :
                            case IToken.t_operator :
                                consume();
                                break;
                            default :
                                return;
                        }
                    }
                    else
                        return;
                    break;
                case IToken.tCOMPL :
                    consume();
                    consume(IToken.tIDENTIFIER);
                    return;
                case IToken.t_operator :
                    consume();
                    //rOperatorName();
                    return;
                default :
                    throw backtrack;
            }
        }
    }
    // the static instance we always use
    private static Backtrack backtrack = new Backtrack();
    // the static instance we always use
    public static EndOfFile endOfFile = new EndOfFile();
    // Token management
    private IScanner scanner;
    private IToken currToken, // current token we plan to consume next 
    lastToken; // last token we consumed
    
    private int highWaterOffset = 0; 
    
    /**
     * Fetches a token from the scanner. 
     * 
     * @return				the next token from the scanner
     * @throws EndOfFile	thrown when the scanner.nextToken() yields no tokens
     */
    private IToken fetchToken() throws EndOfFile
    {
        try
        {
            IToken t = scanner.nextToken();
            if( t.getEndOffset() > highWaterOffset )
            	highWaterOffset = t.getEndOffset();
            return t;
        }
        catch (EndOfFile e)
        {
            throw e;
        }
        catch (ScannerException e)
        {
            Util.debugLog( "ScannerException thrown : " + e.getMessage(), IDebugLogConstants.PARSER );
            return fetchToken();
        }
    }
    /**
     * Look Ahead in the token list to see what is coming.  
     * 
     * @param i		How far ahead do you wish to peek?
     * @return		the token you wish to observe
     * @throws EndOfFile	if looking ahead encounters EOF, throw EndOfFile 
     */
    protected IToken LA(int i) throws EndOfFile
    {
        if (i < 1) // can't go backwards
            return null;
        if (currToken == null)
            currToken = fetchToken();
        IToken retToken = currToken;
        for (; i > 1; --i)
        {
            retToken = retToken.getNext();
            if (retToken == null)
                retToken = fetchToken();
        }
        return retToken;
    }
    /**
     * Look ahead in the token list and return the token type.  
     * 
     * @param i				How far ahead do you wish to peek?
     * @return				The type of that token
     * @throws EndOfFile	if looking ahead encounters EOF, throw EndOfFile
     */
    protected int LT(int i) throws EndOfFile
    {
        return LA(i).getType();
    }
    /**
     * Consume the next token available, regardless of the type.  
     * 
     * @return				The token that was consumed and removed from our buffer.  
     * @throws EndOfFile	If there is no token to consume.  
     */
    protected IToken consume() throws EndOfFile
    {
        if (currToken == null)
            currToken = fetchToken();
        if (currToken != null)
            lastToken = currToken;
        currToken = currToken.getNext();
        return lastToken;
    }
    /**
     * Consume the next token available only if the type is as specified.  
     * 
     * @param type			The type of token that you are expecting.  	
     * @return				the token that was consumed and removed from our buffer. 
     * @throws Backtrack	If LT(1) != type 
     */
    protected IToken consume(int type) throws Backtrack
    {
        if (LT(1) == type)
            return consume();
        else
            throw backtrack;
    }
    /**
     * Mark our place in the buffer so that we could return to it should we have to.  
     * 
     * @return				The current token. 
     * @throws EndOfFile	If there are no more tokens.
     */
    protected IToken mark() throws EndOfFile
    {
        if (currToken == null)
            currToken = fetchToken();
        return currToken;
    }
    /**
     * Rollback to a previous point, reseting the queue of tokens.  
     * 
     * @param mark		The point that we wish to restore to.  
     *  
     */
    protected void backup(IToken mark)
    {
        currToken = (Token)mark;
        lastToken = null; // this is not entirely right ... 
    }
    /* (non-Javadoc)
     * @see org.eclipse.cdt.internal.core.parser.IParser#isCppNature()
     */
    public boolean isCppNature()
    {
        return cppNature;
    }
    /* (non-Javadoc)
     * @see org.eclipse.cdt.internal.core.parser.IParser#setCppNature(boolean)
     */
    public void setCppNature(boolean b)
    {
        cppNature = b;
        if (scanner != null)
            scanner.setCppNature(b);
    }
    /* (non-Javadoc)
     * @see org.eclipse.cdt.internal.core.parser.IParser#getLastErrorOffset()
     */
    public int getLastErrorOffset()
    {
        return firstErrorOffset;
    }

}
