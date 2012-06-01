/** 
 * Copyright (c) 2009-2011, The HATS Consortium. All rights reserved. 
 * This file is licensed under the terms of the Modified BSD License.
 */
package eu.hatsproject.absplugin.editor;

import static eu.hatsproject.absplugin.util.Constants.*;
import static eu.hatsproject.absplugin.util.UtilityFunctions.standardExceptionHandling;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.DefaultCharacterPairMatcher;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ICharacterPairMatcher;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IPersistableEditor;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

import abs.frontend.ast.ASTNode;
import abs.frontend.ast.CompilationUnit;

import eu.hatsproject.absplugin.Activator;
import eu.hatsproject.absplugin.builder.AbsNature;
import eu.hatsproject.absplugin.editor.decoration.ABSDecorationSupport;
import eu.hatsproject.absplugin.editor.outline.ABSContentOutlinePage;
import eu.hatsproject.absplugin.editor.outline.PackageAbsFileEditorInput;
import eu.hatsproject.absplugin.editor.reconciling.ABSReconcilingStrategy;
import eu.hatsproject.absplugin.editor.reconciling.AbsModelManager;
import eu.hatsproject.absplugin.editor.reconciling.CompilationUnitChangeListener;
import eu.hatsproject.absplugin.util.Constants;
import eu.hatsproject.absplugin.util.CoreControlUnit;
import eu.hatsproject.absplugin.util.Images;
import eu.hatsproject.absplugin.util.InternalASTNode;
import eu.hatsproject.absplugin.util.UtilityFunctions;
import eu.hatsproject.absplugin.util.CoreControlUnit.ResourceBuildListener;
import eu.hatsproject.absplugin.util.CoreControlUnit.ResourceBuiltEvent;

/**
 * The editor for ABS file. Includes syntax highlighting and content assist for ABS files
 * as well as annotations for ABS errors.
 * 
 * @author tfischer, cseise, fstrauss, mweber
 *
 */
public class ABSEditor extends TextEditor implements IPersistableEditor, CompilationUnitChangeListener, ISelectionChangedListener{
    
	private final class UpdateEditorIcon implements ResourceBuildListener {
		@Override
		public void resourceBuilt(ResourceBuiltEvent builtevent) {
			final IResource editorres = (IResource)ABSEditor.this.getEditorInput().getAdapter(IResource.class);
			if(builtevent.hasChanged(editorres)){
				
				Display.getDefault().asyncExec(new Runnable() {
					@Override
					public void run() {
						updateEditorIcon(editorres);
						// workaround for squiggly lines not vanishing after rebuild
						//refresh();
						
					}
				});
			}
		}
	}

	private ABSContentOutlinePage outlinePage = null;
	private ResourceBuildListener builtListener;
	private IPropertyChangeListener propertyChangeListener;
	private List<CompilationUnitChangeListener> modelChangeListeners = new ArrayList<CompilationUnitChangeListener>();
	private CompilationUnit compilationUnit;
	private ABSReconcilingStrategy reconciler;
    private AbsNature absNature;
    private volatile int caretPos;
	
	public ABSEditor() {
	    super();
	    setSourceViewerConfiguration(new ABSSourceViewerConfiguration(this));
	    setDocumentProvider(new ABSDocumentProvider());
	    builtListener = new UpdateEditorIcon();
		CoreControlUnit.addResourceBuildListener(builtListener);
		// if preferences of syntax highlighting change: Reinstall the PresentationReconciler to make the changes appear.
		propertyChangeListener = new IPropertyChangeListener() {
			
			@Override
			public void propertyChange(PropertyChangeEvent event) {
				ISourceViewer sourceviewer = ABSEditor.this.getSourceViewer();
				getSourceViewerConfiguration().getPresentationReconciler(sourceviewer).install(sourceviewer);
			}
		};
		Activator.getDefault().getPreferenceStore().addPropertyChangeListener(propertyChangeListener);
		
//		this.getSelectionProvider().addSelectionChangedListener(new ISelectionChangedListener() {
//			
//			@Override
//			public void selectionChanged(SelectionChangedEvent event) {
//				System.out.println("selection = " + event.getSelection());
//				
//			}
//		});
//		this.getSourceViewer().getSelectedRange();
//		StyledText styledText = (StyledText) getAdapter(Control.class);
//		styledText.addCaretListener(new CaretListener() {
//			
//			@Override
//			public void caretMoved(CaretEvent e) {
//				System.out.println(e.caretOffset);
//				
//			}
//		});
		
	}
	
	/**
	 * Reinitializes the editor's source viewer based on the old editor input / document.
	 *
	 */
	private void reinitializeSourceViewer() {
		IEditorInput input = getEditorInput();
		IDocumentProvider documentProvider = getDocumentProvider();
		IAnnotationModel model = documentProvider.getAnnotationModel(input);
		IDocument document = documentProvider.getDocument(input);
		ISourceViewer fSourceViewer = getSourceViewer();
		
		if (document != null) {
			fSourceViewer.setDocument(document, model);
		}
	}
	
	/**
	 * highlights the given line as the current instruction point
	 * @param line the line the debugger is currently running in
	 */
	public void highlightLine(int line){
		IDocument doc = getDocumentProvider().getDocument(getEditorInput());
		int lineOffset;
		try {
			lineOffset = doc.getLineOffset(line);
			
			IResource resource = getResource();
			if (resource != null) { // can be null for files inside jars
			    
    			resource.deleteMarkers(Constants.CURRENT_IP_MARKER, false, IResource.DEPTH_ZERO);
    			
    			getSourceViewer().invalidateTextPresentation();
    			
    			IMarker marker = resource.createMarker(Constants.CURRENT_IP_MARKER);
                marker.setAttribute(IMarker.LINE_NUMBER, line);
                marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                marker.setAttribute(IMarker.MESSAGE, "current instruction pointer");
                
			}
            if(getSourceViewer() instanceof SourceViewer){
                SourceViewer sourceviewer = (SourceViewer)getSourceViewer();
                sourceviewer.setSelection(new TextSelection(lineOffset, 0), true);
                //sourceviewer.refresh();
            }
		} catch (CoreException e) {
			standardExceptionHandling(e);
		} catch (BadLocationException e) {
			standardExceptionHandling(e);
		}
	}
	
	/**
	 * removes the the highlighting set by {@link #highlightLine(int)}.
	 */
	public void removeHighlighting(){
		IResource resource = getResource();
		if (resource == null) {
		    // can be null for files inside jars
			return;
		}
		try {
			resource.deleteMarkers(Constants.CURRENT_IP_MARKER, false, IResource.DEPTH_INFINITE);
			getSourceViewer().invalidateTextPresentation();
		} catch (CoreException e) {
			standardExceptionHandling(e);
		}
	}
	
	@Override
	protected SourceViewerDecorationSupport getSourceViewerDecorationSupport(ISourceViewer viewer) {
		if (fSourceViewerDecorationSupport == null) {
			fSourceViewerDecorationSupport = new ABSDecorationSupport(viewer, getOverviewRuler(), getAnnotationAccess(), getSharedColors());
			configureSourceViewerDecorationSupport(fSourceViewerDecorationSupport);
		}
		return fSourceViewerDecorationSupport;
	}
	
	@Override
	public void init(IEditorSite site, IEditorInput input) throws PartInitException {
		super.init(site, input);
		
		// activate abseditorscope when part is active
		// for example: F3 (JumpToDeclaration) is only active if this editor scope is enabled
	    IContextService cs = (IContextService)getSite().getService(IContextService.class);
	    cs.activateContext(ABSEDITOR_CONTEXT_ID);
	    
	 
	}
	
	@Override
	public void createPartControl(Composite parent) {
	    super.createPartControl(parent);

	    // listen to changes of the caret position:
	    IPostSelectionProvider sp = (IPostSelectionProvider) this.getSelectionProvider();
	    sp.addPostSelectionChangedListener(this);
	    
	    initCompilationUnit();
	}

	private void initCompilationUnit() {
	    absNature = UtilityFunctions.getAbsNature(getProject());
	    if (absNature != null) {
	        AbsModelManager modelManager = absNature.getModelManager();
	        compilationUnit = modelManager.getCompilationUnit(getAbsoluteFilePath());
	    } else {
	        // we are looking at abslang.abs or a file inside a jar-package
	        IURIEditorInput uriInput = (IURIEditorInput) getEditorInput().getAdapter(IURIEditorInput.class);

	        if (uriInput != null) {
	            // We're looking e.g. at abslang.abs which only exists in memory.

	            // create an empty model which only contains abslang.abs:
	            absNature = new AbsNature();
	            absNature.emptyModel();
	            File f = new File(uriInput.getURI());
	            String path = f.getAbsolutePath();
	            compilationUnit = absNature.getCompilationUnit(path);
	        }
	    }
	}

	@Override
	@SuppressWarnings("rawtypes")
	public Object getAdapter(Class key){
		if (IContentOutlinePage.class.equals(key)){
			if(outlinePage == null){
				outlinePage = new ABSContentOutlinePage(this.getDocumentProvider(),this);
			}
			return outlinePage;
		}
		return super.getAdapter(key);
	}
	
	@Override
	protected void configureSourceViewerDecorationSupport (SourceViewerDecorationSupport support) {
		IPreferenceStore store = getPreferenceStore();
		store.setValue(LOCATION_TYPE_NEAR_TEXTSTYLE_KEY, LOCATION_TYPE_NEAR_TEXTSTYLE_VALUE);
		store.setValue(LOCATION_TYPE_FAR_TEXTSTYLE_KEY, LOCATION_TYPE_FAR_TEXTSTYLE_VALUE);
		store.setValue(LOCATION_TYPE_SOMEWHERE_TEXTSTYLE_KEY, LOCATION_TYPE_SOMEWHERE_TEXTSTYLE_VALUE);
		super.configureSourceViewerDecorationSupport(support);
	 
		char[] matchChars = {'(', ')', '[', ']', '{', '}'}; //which brackets to match
		ICharacterPairMatcher matcher = new DefaultCharacterPairMatcher(matchChars ,
				IDocumentExtension3.DEFAULT_PARTITIONING);
		support.setCharacterPairMatcher(matcher);
		support.setMatchingCharacterPainterPreferenceKeys(EDITOR_MATCHING_BRACKETS, EDITOR_MATCHING_BRACKETS_COLOR);
		
		//Enable bracket highlighting in the preference store
		store.setDefault(EDITOR_MATCHING_BRACKETS, true);
		store.setDefault(EDITOR_MATCHING_BRACKETS_COLOR, Constants.DEFAULT_MATCHING_BRACKETS_COLOR);
	}



	@Override
	protected void doSetInput(IEditorInput input) throws CoreException {
		super.doSetInput(input);
		outlinePage = new ABSContentOutlinePage(this.getDocumentProvider(),this);
	}

	public IResource getResource() {
		return (IResource)getEditorInput().getAdapter(IResource.class);
	}

	/**
	 * Throws a {@link SWTException} if the display is disposed
	 * @param editorres the resource of the editor input
	 */
	private void updateEditorIcon(IResource editorres) {
		try {
			int sev = editorres.findMaxProblemSeverity(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
			if(sev == IMarker.SEVERITY_INFO){
				setTitleImage(getEditorInput().getImageDescriptor().createImage());
				return;
			}
			ISharedImages simages = PlatformUI.getWorkbench().getSharedImages();
			ImageDescriptor overlayIcon = null;
			switch(sev){
			case IMarker.SEVERITY_WARNING:
				overlayIcon = simages.getImageDescriptor(ISharedImages.IMG_DEC_FIELD_WARNING);
				break;
			case IMarker.SEVERITY_ERROR:
				overlayIcon = simages.getImageDescriptor(ISharedImages.IMG_DEC_FIELD_ERROR);
				break;
			}
			Image resourceImage = getEditorInput().getImageDescriptor().createImage();
			final DecorationOverlayIcon icon = new DecorationOverlayIcon(resourceImage, overlayIcon, IDecoration.BOTTOM_LEFT);
			setTitleImage(icon.createImage());
		} catch (CoreException e) {
			standardExceptionHandling(e);
		}
	}
	
	@Override
	public void dispose() {
		CoreControlUnit.removeResourceBuildListener(builtListener);
		Activator.getDefault().getPreferenceStore().removePropertyChangeListener(propertyChangeListener);
		super.dispose();
	}

	public void openInformation(String title, String message) {
		MessageDialog.openInformation(getSite().getShell(), title, message);
	}

	public void openError(String title, String message) {
		MessageDialog.openError(getSite().getShell(), title, message);
	}
	
	public IProject getProject() {
		if (getResource() != null) {
			return getResource().getProject();
		}
		PackageAbsFileEditorInput storageInput = (PackageAbsFileEditorInput) getEditorInput().getAdapter(PackageAbsFileEditorInput.class);
		if (storageInput != null) {
			// we are looking at a file inside a jar package
			return storageInput.getFile().getProject();
		}
		return null;
	}

	/**
	 * returns the absolute file path to the file opened by the editor
	 * or "<unknown>" if no such file exists 
	 */
	public String getAbsoluteFilePath() {
		File f = getFile();
		if (f != null) {
			return f.getAbsolutePath();
		}
		PackageAbsFileEditorInput storageInput = (PackageAbsFileEditorInput) getEditorInput().getAdapter(PackageAbsFileEditorInput.class);
		if (storageInput != null) {
			// we are looking at a file inside a jar package
			return storageInput.getFile().getAbsoluteFilePath();
		}
		return "<unknown>";
	}
	
	/**
	 * returns the file shown by the editor
	 * or null if the current resource is not a file
	 */
	public File getFile() {
		if (getResource() instanceof IFile) {
			IFile iFile = (IFile) getResource();
			return iFile.getLocation().toFile();
		}
		return null;
	}
	
	/**
	 * adds a listener which is notified whenever the underlying
	 * compilationUnit of this editor changes
	 */
	public void addModelChangeListener(CompilationUnitChangeListener modelChangeListener) {
		this.modelChangeListeners.add(modelChangeListener);
		if (compilationUnit != null) {
			modelChangeListener.onCompilationUnitChange(compilationUnit);
		}
	}
	
	public void removeModelChangeListener(CompilationUnitChangeListener modelChangeListener) {
		this.modelChangeListeners.remove(modelChangeListener);
	}
	
	@Override
	public void onCompilationUnitChange(CompilationUnit newCu) {
		this.compilationUnit = newCu;
		for (CompilationUnitChangeListener mcl : modelChangeListeners) {
			mcl.onCompilationUnitChange(newCu);
		}
		
	}

	/**
	 * parses the current contents of the editor
	 * and updates the compilationUnit
	 * @param documentOffset 
	 */
	public void reconcile(boolean withTypechecks) {
	    if (reconciler != null) {
	        reconciler.reconcile(absNature, withTypechecks);
	    }
	}

	public void setReconciler(ABSReconcilingStrategy absReconcilingStrategy) {
		this.reconciler = absReconcilingStrategy;
		
	}

	@Override
	public void selectionChanged(SelectionChangedEvent event) {
		ISelection selection = event.getSelection();
		if (selection instanceof ITextSelection) {
		    // select the current element in the outlineView
			ITextSelection ts = (ITextSelection) selection;
			caretPos = ts.getOffset();
			outlinePage.selectNodeByPos(ts.getStartLine());
		}
	}

	/**
	 * returns the current compilationUnit or null 
	 * when viewing files outside an ABS project
	 */
	public synchronized InternalASTNode<CompilationUnit> getCompilationUnit() {
	    if (absNature == null || compilationUnit == null) {
	        return null;
	    }
            return new InternalASTNode<CompilationUnit>(compilationUnit, absNature);
	}

    public AbsNature getAbsNature() {
        return absNature;
    }

    public void setCaretPos(int caretPos) {
        this.caretPos = caretPos;
    }
    
    public int getCaretPos() {
        return caretPos;
    }
	
}