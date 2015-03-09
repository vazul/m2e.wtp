/*******************************************************************************
 * Copyright (c) 2008-2014 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.m2e.wtp.internal.filtering;


import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.Scanner;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.wtp.DomUtils;
import org.eclipse.m2e.wtp.MavenWtpPlugin;
import org.eclipse.m2e.wtp.WTPProjectsUtil;
import org.eclipse.m2e.wtp.internal.Messages;
import org.eclipse.osgi.util.NLS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.sonatype.plexus.build.incremental.ThreadBuildContext;

/**
 * ResourceFilteringBuildParticipant
 *
 * @author Fred Bricon
 */
public class ResourceFilteringBuildParticipant extends AbstractBuildParticipant {
  
  private static final Logger LOG = LoggerFactory.getLogger(ResourceFilteringBuildParticipant.class );

  private CleanBuildContext forceCopyBuildContext; 
  
  @Override
  public Set<IProject> build(int kind, IProgressMonitor monitor) throws Exception {
    IMavenProjectFacade facade = getMavenProjectFacade();
    ResourceFilteringConfiguration configuration = ResourceFilteringConfigurationFactory.getConfiguration(facade);
    List<Xpp3Dom> resources = null;
    if (configuration == null || (resources = configuration.getResources()) == null) {
      //Nothing to filter
      return null;
    }

    IProject project = facade.getProject();
    //FIXME assuming path relative to current project
    IPath targetFolder = configuration.getTargetFolder();
    IResourceDelta delta = getDelta(project);

    BuildContext oldBuildContext = ThreadBuildContext.getContext();
    
    try {
      forceCopyBuildContext = null;
      List<String> filters = configuration.getFilters();
      if (changeRequiresForcedCopy(facade, filters, delta)) {
        LOG.info(NLS.bind(Messages.ResourceFilteringBuildParticipant_Changed_Resources_Require_Clean_Build,project.getName()));
        //String id = "" + "-" + getClass().getName();
        forceCopyBuildContext = new CleanBuildContext(oldBuildContext);
        ThreadBuildContext.setThreadBuildContext(forceCopyBuildContext);
      }
      if (forceCopyBuildContext != null || hasResourcesChanged(facade, delta, resources)) {
        LOG.info(NLS.bind(Messages.ResourceFilteringBuildParticipant_Executing_Resource_Filtering,project.getName()));
        executeCopyResources(facade, configuration, targetFolder, resources, monitor);
        //FIXME deal with absolute paths
        IFolder destFolder = project.getFolder(targetFolder);
        if (destFolder.exists()){
          destFolder.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        }
      }
    } finally {
      ThreadBuildContext.setThreadBuildContext(oldBuildContext);
    }

    return null;
  }

  @Override
  protected BuildContext getBuildContext() {
     return (forceCopyBuildContext == null)?super.getBuildContext() : forceCopyBuildContext;
  }

   /**
  * If the pom.xml or any of the project's filters were changed, a forced copy is required
  * @param facade
  * @param delta
  * @return
  */
   private boolean changeRequiresForcedCopy(IMavenProjectFacade facade, List<String> filters, IResourceDelta delta) {
     if (delta == null) {
       return false;
     }
  
     if (delta.findMember(facade.getPom().getProjectRelativePath()) != null ) {
       return true;
     }
     
     for (String filter : filters) {       
       IPath filterPath = facade.getProjectRelativePath(filter);
       if (filterPath == null) {
         filterPath =Path.fromOSString(filter);
       }
       if (delta.findMember(filterPath) != null){
         return true;
       }
     }
     return false;
     
   }
 
  @Override
public void clean(IProgressMonitor monitor) throws CoreException {
    IMavenProjectFacade facade = getMavenProjectFacade();
    ResourceFilteringConfiguration configuration = ResourceFilteringConfigurationFactory.getConfiguration(facade);
    if (configuration == null) {
      //Nothing to do
      return;
    }

    IProject project = facade.getProject();
    IPath targetFolderPath = configuration.getTargetFolder();
    deleteFilteredResources(project, targetFolderPath);
    super.clean(monitor);
  }
  
  private void deleteFilteredResources(IProject project, IPath targetFolderPath) throws CoreException {
    IFolder targetFolder = project.getFolder(targetFolderPath);
    if (targetFolder.exists()) {
      IContainer parent = targetFolder.getParent(); 
      LOG.info(NLS.bind(Messages.ResourceFilteringBuildParticipant_Cleaning_Filtered_Folder,project.getName()));
      IProgressMonitor monitor =new NullProgressMonitor();
      targetFolder.delete(true, monitor);
      if (parent != null) {
        parent.refreshLocal(IResource.DEPTH_INFINITE, monitor ); 
      }
    }    
  }

  
  /**
   * @param mavenProject
   * @param iResourceDelta 
   * @param resources
   * @return
   */
  private boolean hasResourcesChanged(IMavenProjectFacade facade, IResourceDelta delta, List<Xpp3Dom> resources) {
    if (resources == null || resources.isEmpty()){
      return false;
    }
      
    Set<IPath> resourcePaths = getResourcePaths(facade, resources);
  
    if(delta == null) {
      return !resourcePaths.isEmpty();
    }
  
    for(IPath resourcePath : resourcePaths) {
      IResourceDelta member = delta.findMember(resourcePath);
      //XXX deal with member kind/flags
      if(member != null) {
          return true; 
          //we need to deal with superceded resources on the maven level
      }
    }
  
    return false;
  }

  
  private Set<IPath> getResourcePaths(IMavenProjectFacade facade, List<Xpp3Dom> resources) {
    Set<IPath> resourcePaths = new LinkedHashSet<IPath>();
    for(Xpp3Dom resource : resources) {
      IPath folder= null;
      Xpp3Dom xpp3Directory = resource.getChild("directory"); //$NON-NLS-1$
      if (xpp3Directory != null)
      {
        String dir = xpp3Directory.getValue();
        if (StringUtils.isNotEmpty(dir)){
          folder = WTPProjectsUtil.tryProjectRelativePath(facade.getProject(), dir);          
        }
      }
      if(folder != null && !folder.isEmpty()) {
        resourcePaths.add(folder);
      }
    }

    return resourcePaths;
  }
  

  private void executeCopyResources(IMavenProjectFacade facade,  ResourceFilteringConfiguration filteringConfiguration, IPath targetFolder, List<Xpp3Dom> resources, IProgressMonitor monitor) throws CoreException {

    //Create a maven request + session
    List<String> filters = filteringConfiguration.getFilters();

    IMaven maven = MavenPlugin.getMaven();
    MavenProject mavenProject = facade.getMavenProject();
    
		List<MojoExecution> mojoExecutions = facade.getMojoExecutions("org.apache.maven.plugins", "maven-resources-plugin", monitor, "copy-resources");//$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		if (mojoExecutions.size() > 1)
		{
			throw new CoreException(new Status(IStatus.ERROR, MavenWtpPlugin.ID, "There is no exact copy-resources plugin configured: " + mojoExecutions.size())); //$NON-NLS-1$
		}

		if (mojoExecutions.size() == 0)
			return;
    
		MojoExecution copyFilteredResourcesMojo = mojoExecutions.get(0);


    Xpp3Dom originalConfig = copyFilteredResourcesMojo.getConfiguration();
    Xpp3Dom  configuration = Xpp3DomUtils.mergeXpp3Dom(new Xpp3Dom("configuration"), originalConfig); //$NON-NLS-1$
    try {
      
      //Set resource directories to read
      setupResources(configuration, resources);
      
      //Force overwrite
      setValue(configuration, "overwrite", Boolean.TRUE); //$NON-NLS-1$
      
      //Limit placeholder delimiters, otherwise, pages containing @ wouldn't be filtered correctly
      setupDelimiters(configuration);
      
      //Set output directory to the m2eclipse-wtp webresources directory
      setValue(configuration, "outputDirectory", targetFolder.toPortableString()); //$NON-NLS-1$
      
      setValue(configuration, "escapeString", filteringConfiguration.getEscapeString()); //$NON-NLS-1$

      setNonfilteredExtensions(configuration, filteringConfiguration.getNonfilteredExtensions());

      //Setup filters
      setupFilters(configuration, filters);

      //Execute our modified mojo 
      copyFilteredResourcesMojo.setConfiguration(configuration);
      copyFilteredResourcesMojo.getMojoDescriptor().setGoal("copy-resources"); //$NON-NLS-1$
      
			maven.execute(mavenProject, copyFilteredResourcesMojo, monitor);
      
    } finally {
      //Restore original configuration
      copyFilteredResourcesMojo.setConfiguration(originalConfig);
    }
  }

  /**
   * @param configuration
   * @param extensions
   */
  private void setNonfilteredExtensions(Xpp3Dom configuration, List<Xpp3Dom> extensions) {
    if (extensions == null || extensions.isEmpty()) {
      return;
    }
    Xpp3Dom nonFilteredFileExtensionsNode = configuration.getChild("nonFilteredFileExtensions"); //$NON-NLS-1$
    if (nonFilteredFileExtensionsNode == null) {
      nonFilteredFileExtensionsNode = new Xpp3Dom("nonFilteredFileExtensions"); //$NON-NLS-1$
      configuration.addChild(nonFilteredFileExtensionsNode);
    } else {
      DomUtils.removeChildren(nonFilteredFileExtensionsNode);
    }
    
    for (Xpp3Dom ext : extensions) {
      nonFilteredFileExtensionsNode.addChild(ext);
    }
  }

  private void setValue(Xpp3Dom configuration, String childName, Object value) {
    Xpp3Dom  childNode = configuration.getChild(childName);
    if (childNode==null){
      childNode = new Xpp3Dom(childName);
      configuration.addChild(childNode);
    }
    childNode.setValue((value == null)?null:value.toString());
  }

  private void setupFilters(Xpp3Dom configuration, List<String> filters) {
    if (!filters.isEmpty()) {
      Xpp3Dom  filtersNode = configuration.getChild("filters"); //$NON-NLS-1$
      
      if (filtersNode==null){
        filtersNode = new Xpp3Dom("filters"); //$NON-NLS-1$
        configuration.addChild(filtersNode);
      } else {
        DomUtils.removeChildren(filtersNode);
      }
      
      for (String filter : filters) {
        Xpp3Dom filterNode = new Xpp3Dom("filter"); //$NON-NLS-1$
        //Workaround : when run via the BuildParticipant, the maven-resource-plugin won't 
        //find a filter defined with a relative path, so we turn it into an absolute one
        IPath filterPath = new Path(filter);
        boolean isAbsolute = false;
        if (filter.startsWith("${basedir}") ||filter.startsWith("/") || filterPath.getDevice() != null) { //$NON-NLS-1$ //$NON-NLS-2$
          isAbsolute = true;
        }
        String filterAbsolutePath;
        if (isAbsolute) {
          filterAbsolutePath = filter;
        } else {
          filterAbsolutePath = "${basedir}/"+filter; //$NON-NLS-1$
        }

        filterNode.setValue(filterAbsolutePath);
        filtersNode.addChild(filterNode );
      }
    }
  }
  
  private void setupDelimiters(Xpp3Dom configuration) {
    Xpp3Dom  useDefaultDelimitersNode = configuration.getChild("useDefaultDelimiters"); //$NON-NLS-1$
    if (useDefaultDelimitersNode==null){
      useDefaultDelimitersNode = new Xpp3Dom("useDefaultDelimiters"); //$NON-NLS-1$
      configuration.addChild(useDefaultDelimitersNode);
    }
    useDefaultDelimitersNode.setValue(Boolean.FALSE.toString());

    Xpp3Dom  delimitersNode = configuration.getChild("delimiters"); //$NON-NLS-1$
    if (delimitersNode==null){
      delimitersNode = new Xpp3Dom("delimiters"); //$NON-NLS-1$
      configuration.addChild(delimitersNode);
    } else {
      DomUtils.removeChildren(delimitersNode);
    }
    Xpp3Dom delimiter = new Xpp3Dom("delimiter"); //$NON-NLS-1$
    delimiter.setValue("${*}"); //$NON-NLS-1$
    delimitersNode.addChild(delimiter);
  }
  

  private void setupResources(Xpp3Dom configuration, List<Xpp3Dom> resources) {
    Xpp3Dom  resourcesNode = configuration.getChild("resources"); //$NON-NLS-1$
    if (resourcesNode==null){
      resourcesNode = new Xpp3Dom("resources"); //$NON-NLS-1$
      configuration.addChild(resourcesNode);
    } else {
      resourcesNode.setAttribute("default-value", ""); //$NON-NLS-1$ //$NON-NLS-2$
      DomUtils.removeChildren(resourcesNode);
    }
    for (Xpp3Dom resource : resources)
    {
      resourcesNode.addChild(resource);
    }
  }
  

  private static class CleanBuildContext implements BuildContext {

	private BuildContext originalContext;

	CleanBuildContext(BuildContext originalContext) {
		this.originalContext = originalContext;
	}
	  
		@Override
	public boolean hasDelta(String relpath) {
		return true;
	}

		@Override
	public boolean hasDelta(File file) {
		return true;
	}

		@SuppressWarnings("rawtypes")
		@Override
	public boolean hasDelta(List relpaths) {
		return true;
	}

		@Override
	public void refresh(File file) {
		originalContext.refresh(file);
	}

		@Override
	public OutputStream newFileOutputStream(File file) throws IOException {
		return originalContext.newFileOutputStream(file);
	}

		@Override
	public Scanner newScanner(File basedir) {
		return originalContext.newScanner(basedir);
	}

		@Override
	public Scanner newDeleteScanner(File basedir) {
		return originalContext.newDeleteScanner(basedir);
	}

		@Override
	public Scanner newScanner(File basedir, boolean ignoreDelta) {
		return originalContext.newScanner(basedir, ignoreDelta);
	}

		@Override
	public boolean isIncremental() {
		return false;
	}

		@Override
	public void setValue(String key, Object value) {
		originalContext.setValue(key, value);
	}

		@Override
	public Object getValue(String key) {
		return originalContext.getValue(key);
	}

		@SuppressWarnings("deprecation")
		@Override
	public void addWarning(File file, int line, int column, String message,
			Throwable cause) {
		originalContext.addWarning(file, line, column, message, cause);
	}

		@SuppressWarnings("deprecation")
		@Override
	public void addError(File file, int line, int column, String message,
			Throwable cause) {
		originalContext.addError(file, line, column, message, cause);
	}

		@Override
	public void addMessage(File file, int line, int column, String message,
			int severity, Throwable cause) {
		originalContext.addMessage(file, line, column, message, severity, cause);
	}

		@Override
	public void removeMessages(File file) {
		originalContext.removeMessages(file);
	}

		@Override
	public boolean isUptodate(File target, File source) {
		return false;
	}

  }
}
