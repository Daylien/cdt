/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.internal.ui.viewsupport;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProviderChangedEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.ui.texteditor.MarkerUtilities;

import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ISourceRange;
import org.eclipse.cdt.core.model.ISourceReference;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.ICResourceDescription;
import org.eclipse.cdt.ui.CElementImageDescriptor;
import org.eclipse.cdt.ui.CUIPlugin;

import org.eclipse.cdt.internal.ui.CPluginImages;
import org.eclipse.cdt.internal.ui.util.IProblemChangedListener;
import org.eclipse.cdt.internal.ui.util.ImageDescriptorRegistry;

/**
 * LabelDecorator that decorates an element's image with error and warning overlays that 
 * represent the severity of markers attached to the element's underlying resource. To see 
 * a problem decoration for a marker, the marker needs to be a subtype of <code>IMarker.PROBLEM</code>.
 * <p>
 * Note: Only images for elements in Java projects are currently updated on marker changes.
 * </p>
 * 
 * @since 2.0
 */
public class ProblemsLabelDecorator implements ILabelDecorator, ILightweightLabelDecorator {
	
	/**
	 * This is a special <code>LabelProviderChangedEvent</code> carring additional 
	 * information whether the event orgins from a maker change.
	 * <p>
	 * <code>ProblemsLabelChangedEvent</code>s are only generated by <code>
	 * ProblemsLabelDecorator</code>s.
	 * </p>
	 */
	public static class ProblemsLabelChangedEvent extends LabelProviderChangedEvent {

		private boolean fMarkerChange;

		/**
		 * Note: This constructor is for internal use only. Clients should not call this constructor.
		 */
		public ProblemsLabelChangedEvent(IBaseLabelProvider source, IResource[] changedResource, boolean isMarkerChange) {
			super(source, changedResource);
			fMarkerChange= isMarkerChange;
		}
		
		/**
		 * Returns whether this event origins from marker changes. If <code>false</code> an annotation 
		 * model change is the origin. In this case viewers not displaying working copies can ignore these 
		 * events.
		 * 
		 * @return if this event origins from a marker change.
		 */
		public boolean isMarkerChange() {
			return fMarkerChange;
		}

	}

	private static final int ERRORTICK_WARNING= CElementImageDescriptor.WARNING;
	private static final int ERRORTICK_ERROR= CElementImageDescriptor.ERROR;	
	private static final int TICK_CONFIGURATION = CElementImageDescriptor.SYSTEM_INCLUDE;	
	private static final int TICK_EXCLUDE       = CElementImageDescriptor.INACTIVE;	
	
	private ImageDescriptorRegistry fRegistry;
	private boolean fUseNewRegistry= false;
	private IProblemChangedListener fProblemChangedListener;
	
	private ListenerList fListeners;

	/**
	 * Creates a new <code>ProblemsLabelDecorator</code>.
	 */
	public ProblemsLabelDecorator() {
		this(null);
		fUseNewRegistry= true;
	}
	
	/*
	 * Creates decorator with a shared image registry.
	 * 
	 * @param registry The registry to use or <code>null</code> to use the Java plugin's
	 * image registry.
	 */
	/**
	 * Note: This constructor is for internal use only. Clients should not call this constructor.
	 */
	public ProblemsLabelDecorator(ImageDescriptorRegistry registry) {
		fRegistry= registry;
		fProblemChangedListener= null;
	}
	
	private ImageDescriptorRegistry getRegistry() {
		if (fRegistry == null) {
			fRegistry= fUseNewRegistry ? new ImageDescriptorRegistry() : CUIPlugin.getImageDescriptorRegistry();
		}
		return fRegistry;
	}
	

	/* (non-Javadoc)
	 * @see ILabelDecorator#decorateText(String, Object)
	 */
	public String decorateText(String text, Object element) {
		return text;
	}	

	/* (non-Javadoc)
	 * @see ILabelDecorator#decorateImage(Image, Object)
	 */
	public Image decorateImage(Image image, Object obj) {
		int adornmentFlags= computeAdornmentFlags(obj);
		if (adornmentFlags != 0) {
			ImageDescriptor baseImage= new ImageImageDescriptor(image);
			Rectangle bounds= image.getBounds();
			return getRegistry().get(new CElementImageDescriptor(baseImage, adornmentFlags, new Point(bounds.width, bounds.height)));
		}
		return image;
	}

	/**
	 * Note: This method is for internal use only. Clients should not call this method.
	 */
	protected int computeAdornmentFlags(Object obj) {
		try {
			if (obj instanceof ICElement) {
				ICElement element= (ICElement) obj;
				int type= element.getElementType();
				switch (type) {
					case ICElement.C_PROJECT:
					case ICElement.C_CCONTAINER:
						return getErrorTicksFromMarkers(element.getResource(), IResource.DEPTH_INFINITE, null) | getTicks(element.getResource());
					case ICElement.C_UNIT:
						return getErrorTicksFromMarkers(element.getResource(), IResource.DEPTH_ONE, null) | getTicks(element.getResource());
					case ICElement.C_FUNCTION:
					case ICElement.C_CLASS:
					case ICElement.C_UNION:
					case ICElement.C_STRUCT:
					case ICElement.C_VARIABLE:
					case ICElement.C_METHOD:
						ITranslationUnit tu= ((ISourceReference)element).getTranslationUnit();
						if (tu != null && tu.exists()) {
							return getErrorTicksFromMarkers(tu.getResource(), IResource.DEPTH_ONE, (ISourceReference)element);
						}
					default:
				}
			} else if (obj instanceof IResource) {
				return getErrorTicksFromMarkers((IResource) obj, IResource.DEPTH_INFINITE, null) | getTicks((IResource)obj);
			}
		} catch (CoreException e) {
			if (e instanceof CModelException) {
//				if (((CModelException) e).isDoesNotExist()) {
//					return 0;
//				}
			}
			if (e.getStatus().getCode() == IResourceStatus.MARKER_NOT_FOUND) {
				return 0;
			}
			
			CUIPlugin.getDefault().log(e);
		}
		return 0;
	}

	private int getErrorTicksFromMarkers(IResource res, int depth, ISourceReference sourceElement) throws CoreException {
		if (res == null || !res.isAccessible()) {
			return 0;
		}
		int info= 0;
		
		IMarker[] markers= res.findMarkers(IMarker.PROBLEM, true, depth);
		if (markers != null) {
			for (int i= 0; i < markers.length && (info != ERRORTICK_ERROR); i++) {
				IMarker curr= markers[i];
				if (sourceElement == null || isMarkerInRange(curr, sourceElement)) {
					int priority= curr.getAttribute(IMarker.SEVERITY, -1);
					if (priority == IMarker.SEVERITY_WARNING) {
						info= ERRORTICK_WARNING;
					} else if (priority == IMarker.SEVERITY_ERROR) {
						info= ERRORTICK_ERROR;
					}
				}
			}			
		}
		return info;
	}

	private boolean isMarkerInRange(IMarker marker, ISourceReference sourceElement) throws CoreException {
		if (marker.isSubtypeOf(IMarker.TEXT)) {
			int pos= marker.getAttribute(IMarker.CHAR_START, -1);
			if (pos == -1) {
				int line= MarkerUtilities.getLineNumber(marker);
				if (line >= 0) {
					return isInside( -1, line, sourceElement);
				}
			}
			return isInside(pos, -1, sourceElement);
			
		}
		return false;
	}
	
	
//	private int getErrorTicksFromWorkingCopy(ITranslationUnit original, ISourceReference sourceElement) throws CoreException {
//		int info= 0;
//		FileEditorInput editorInput= new FileEditorInput((IFile) original.getResource());
//		IAnnotationModel model= CUIPlugin.getDefault().getTranslationUnitDocumentProvider().getAnnotationModel(editorInput);
//		if (model != null) {
//			Iterator iter= model.getAnnotationIterator();
//			while ((info != ERRORTICK_ERROR) && iter.hasNext()) {
//				Annotation curr= (Annotation) iter.next();
//				IMarker marker= isAnnotationInRange(model, curr, sourceElement);
//				if (marker != null) {
//					int priority= marker.getAttribute(IMarker.SEVERITY, -1);
//					if (priority == IMarker.SEVERITY_WARNING) {
//						info= ERRORTICK_WARNING;
//					} else if (priority == IMarker.SEVERITY_ERROR) {
//						info= ERRORTICK_ERROR;
//					}
//				}
//			}
//		}
//		return info;
//	}
			
//	private IMarker isAnnotationInRange(IAnnotationModel model, Annotation annot, ISourceReference sourceElement) throws CoreException {
//		if (annot instanceof MarkerAnnotation) {
//			IMarker marker= ((MarkerAnnotation) annot).getMarker();
//			if (marker.exists() && marker.isSubtypeOf(IMarker.PROBLEM)) {
//				Position pos= model.getPosition(annot);
//				if (pos != null && (sourceElement == null || isInside(pos.getOffset(), -1, sourceElement))) {
//					return marker;
//				}
//			}
//		}
//		return null;
//	}
	
	/**
	 * Tests if a position is inside the source range of an element. Usually this is done
	 * by looking at the offset. In case the offset equals <code>-1</code>, the line is 
	 * tested.
	 * @param offSet offset to be tested
	 * @param line line to be tested
	 * @param sourceElement Source element (must be a ICElement)
	 * @return boolean Return <code>true</code> if position is located inside the source element.
	 * @throws CoreException Exception thrown if element range could not be accessed.
	 * 
	 * @since 2.1
	 */
	protected boolean isInside(int offSet, int line, ISourceReference sourceElement) throws CoreException {
		ISourceRange range= sourceElement.getSourceRange();
		if (range != null) {
			if (offSet ==-1) {
				return (line >= range.getStartLine() && line <= range.getEndLine());
			}
			int rangeOffset= range.getStartPos();
			return (rangeOffset <= offSet && rangeOffset + range.getLength() > offSet);			
		}
		return false;
	}	
	
	/* (non-Javadoc)
	 * @see IBaseLabelProvider#dispose()
	 */
	public void dispose() {
		if (fProblemChangedListener != null) {
			CUIPlugin.getDefault().getProblemMarkerManager().removeListener(fProblemChangedListener);
			fProblemChangedListener= null;
		}
		if (fRegistry != null && fUseNewRegistry) {
			fRegistry.dispose();
		}
	}

	/* (non-Javadoc)
	 * @see IBaseLabelProvider#isLabelProperty(Object, String)
	 */
	public boolean isLabelProperty(Object element, String property) {
		return true;
	}
	
	/* (non-Javadoc)
	 * @see IBaseLabelProvider#addListener(ILabelProviderListener)
	 */
	public void addListener(ILabelProviderListener listener) {
		if (fListeners == null) {
			fListeners= new ListenerList();
		}
		fListeners.add(listener);
		if (fProblemChangedListener == null) {
			fProblemChangedListener= new IProblemChangedListener() {
				public void problemsChanged(IResource[] changedResources, boolean isMarkerChange) {
					fireProblemsChanged(changedResources, isMarkerChange);
				}
			};
			CUIPlugin.getDefault().getProblemMarkerManager().addListener(fProblemChangedListener);
		}
	}	

	/* (non-Javadoc)
	 * @see IBaseLabelProvider#removeListener(ILabelProviderListener)
	 */
	public void removeListener(ILabelProviderListener listener) {
		if (fListeners != null) {
			fListeners.remove(listener);
			if (fListeners.isEmpty() && fProblemChangedListener != null) {
				CUIPlugin.getDefault().getProblemMarkerManager().removeListener(fProblemChangedListener);
				fProblemChangedListener= null;
			}
		}
	}
	
	void fireProblemsChanged(IResource[] changedResources, boolean isMarkerChange) {
		if (fListeners != null && !fListeners.isEmpty()) {
			LabelProviderChangedEvent event= new ProblemsLabelChangedEvent(this, changedResources, isMarkerChange);
			Object[] listeners= fListeners.getListeners();
			for (int i= 0; i < listeners.length; i++) {
				((ILabelProviderListener) listeners[i]).labelProviderChanged(event);
			}
		}
	}
		
	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.ILightweightLabelDecorator#decorate(java.lang.Object, org.eclipse.jface.viewers.IDecoration)
	 */
	public void decorate(Object element, IDecoration decoration) { 
		int adornmentFlags= computeAdornmentFlags(element);

		if ((adornmentFlags & TICK_CONFIGURATION) != 0) {
			decoration.addOverlay(CPluginImages.DESC_OVR_SYSTEM_INCLUDE);
			adornmentFlags &= ~TICK_CONFIGURATION;
		}
		if ((adornmentFlags & TICK_EXCLUDE) != 0) {
			decoration.addOverlay(CPluginImages.DESC_OVR_INACTIVE);
			adornmentFlags &= ~TICK_EXCLUDE;
		}
		
		if (adornmentFlags == ERRORTICK_ERROR) {
			decoration.addOverlay(CPluginImages.DESC_OVR_ERROR);
		} else if (adornmentFlags == ERRORTICK_WARNING) {
			decoration.addOverlay(CPluginImages.DESC_OVR_WARNING);
		}  
	}

	private int getTicks (IResource r) {
		if (r == null || r instanceof IProject) return 0;		
		IPath path = r.getProjectRelativePath();
		ICProjectDescription prjd = CoreModel.getDefault().getProjectDescription(r.getProject(), false);
		int result = 0;
		if (prjd != null) { 
			ICConfigurationDescription [] cf = prjd.getConfigurations();
			if (cf == null) return 0;
			for (int i=0; i<cf.length; i++) {
				if (cf[i].isActive()) {
					ICResourceDescription out = cf[i].getResourceDescription(path, true);
					if (out != null) result |= TICK_CONFIGURATION;
//					out = cf[i].getResourceDescription(path, false);
					if (out != null && out.isExcluded()) result |= TICK_EXCLUDE;
				}
			}
		}
		return result;
	}
}
