/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.m2e.wtp;

import java.io.IOException;
import java.io.InputStream;

import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jst.j2ee.internal.J2EEVersionConstants;
import org.eclipse.jst.j2ee.project.facet.IJ2EEFacetConstants;
import org.eclipse.jst.jee.util.internal.JavaEEQuickPeek;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.wtp.internal.Messages;
import org.eclipse.osgi.util.NLS;
import org.eclipse.wst.common.project.facet.core.IFacetedProject;
import org.eclipse.wst.common.project.facet.core.IProjectFacetVersion;
import org.eclipse.wst.common.project.facet.core.ProjectFacetsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * maven-rar-plugin configuration model.
 * 
 * @see <a href="maven.apache.org/plugins/maven-rar-plugin/rar-mojo.html">maven.apache.org/plugins/maven-rar-plugin/rar-mojo.html</a>
 * 
 * @provisional This class has been added as part of a work in progress. 
 * It is not guaranteed to work or remain the same in future releases. 
 * For more information contact <a href="mailto:m2e-wtp-dev@eclipse.org">m2e-wtp-dev@eclipse.org</a>.
 * 
 * @author Fred Bricon
 */
public class RarPluginConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(RarPluginConfiguration.class) ;

  private static final String RAR_DEFAULT_CONTENT_DIR = "src/main/rar";  //$NON-NLS-1$

  private static final String RA_XML = "META-INF/ra.xml"; //$NON-NLS-1$

  private static final int JCA_1_6_ID = 16;//Exists in WTP >= 3.2 only

  private static final int JCA_1_7_ID = 17;//Exists in WTP >= 3.5 only


  final Plugin plugin;
  
  final IMavenProjectFacade rarFacade;
  
  
  public RarPluginConfiguration(IMavenProjectFacade facade) throws CoreException {
    Assert.isNotNull(facade);
    if (JEEPackaging.RAR != JEEPackaging.getValue(facade.getPackaging()))
      throw new IllegalArgumentException(Messages.RarPluginConfiguration_Project_Must_Have_rar_Packaging);
    
    this.rarFacade = facade;
    this.plugin = facade.getMavenProject(new NullProgressMonitor()).getPlugin("org.apache.maven.plugins:maven-rar-plugin"); //$NON-NLS-1$
  }

  /**
   * @return rar plugin configuration or null.
   */
  private Xpp3Dom getConfiguration() {
    if(plugin == null) {
      return null;
    }
    return (Xpp3Dom) plugin.getConfiguration();
  }

  /**
   * Should project classes be included in the resulting RAR?
   * @return the value of "includeJar". Default is true;
   */
  public boolean isJarIncluded() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom includeJarDom = config.getChild("includeJar"); //$NON-NLS-1$
      if (includeJarDom != null) {
        return Boolean.parseBoolean(includeJarDom.getValue().trim());
      }
    }
    return true; 
  }
  
  
  /**
   * Gets the rar content directory of the project from pom.xml configuration.
   * 
   * @return the first resource directory found in pom.xml.
   */
  public String getRarContentDirectory() {
    IProject project = rarFacade.getProject();
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom contentDirDom = config.getChild("rarSourceDirectory"); //$NON-NLS-1$
      if(contentDirDom != null && contentDirDom.getValue() != null) {
        String contentDir = contentDirDom.getValue().trim();
        contentDir = ProjectUtils.getRelativePath(project, contentDir);
        contentDir = (contentDir.length() == 0) ? RAR_DEFAULT_CONTENT_DIR : contentDir;
        return contentDir;
      }
    }

    return RAR_DEFAULT_CONTENT_DIR;
  }

  /**
   * @return
   */
  public IProjectFacetVersion getConnectorFacetVersion() {
      IFile raXml = getRaXml();
      
      if(raXml != null && raXml.isAccessible()) {
        try {
          InputStream is = raXml.getContents();
          try {
            JavaEEQuickPeek jqp = new JavaEEQuickPeek(is);
            switch(jqp.getVersion()) {
              case J2EEVersionConstants.JCA_1_0_ID:
                return IJ2EEFacetConstants.JCA_10;
              case J2EEVersionConstants.JCA_1_5_ID:
                return IJ2EEFacetConstants.JCA_15;
              case JCA_1_6_ID:
                //Don't create a static 1.6 facet version, it'd blow up WTP < 3.2
                return IJ2EEFacetConstants.JCA_FACET.getVersion("1.6");//only exists in WTP version >= 3.2 //$NON-NLS-1$
              case JCA_1_7_ID:
                //Don't create a static 1.7 facet version, it'd blow up WTP < 3.5
                return IJ2EEFacetConstants.JCA_FACET.getVersion("1.7");//only exists in WTP version >= 3.5 //$NON-NLS-1$
            }
          } finally {
            is.close();
          }
        } catch(IOException ex) {
          // expected
        } catch(CoreException ex) {
          // expected
        }
      }

      IProject project = rarFacade.getProject();
      //If no rar.xml found, don't change existing facet version
      try {
          IFacetedProject fProject = ProjectFacetsManager.create(project);
          if (fProject != null && fProject.hasProjectFacet(IJ2EEFacetConstants.JCA_FACET)) {
            return fProject.getProjectFacetVersion(IJ2EEFacetConstants.JCA_FACET);
          }
        } catch (Exception e) {
          LOG.warn(NLS.bind(Messages.Error_Reading_Project_Facet, project.getName()), e); 
        }      
      
      //If no ra.xml found and the project depends and WTP >= 3.2, then set connector facet to 1.6
      //TODO see if other conditions might apply to differentiate JCA 1.6 from 1.5
      return IJ2EEFacetConstants.JCA_FACET.getVersion("1.6"); //$NON-NLS-1$
    }

  /**
   * Get the custom location of ra.xml, as set in &lt;raXmlFile&gt;.
   * @return the custom location of ra.xml or null if &lt;raXmlFile&gt; is not set
   */
  public String getCustomRaXml() {
    Xpp3Dom config = getConfiguration();
    if(config != null) {
      Xpp3Dom raXmlFileDom = config.getChild("raXmlFile"); //$NON-NLS-1$
      if(raXmlFileDom != null && raXmlFileDom.getValue() != null) {
        String raXmlFile = raXmlFileDom.getValue().trim();
        raXmlFile = ProjectUtils.getRelativePath(rarFacade.getProject(), raXmlFile);
        return raXmlFile;
      }
    }
    return null;
  }

  public IFile getRaXml() {
    IProject project = rarFacade.getProject();
    String customRaXmlPath = getCustomRaXml();
    IFile raXml = null;
    if (customRaXmlPath != null ) {
      raXml = project.getFile(customRaXmlPath);
    }
    if (raXml == null || !raXml.isAccessible()) {
      raXml = project.getFolder(getRarContentDirectory()).getFile(RA_XML);
    }
    if (!raXml.isAccessible()) {
      for (IPath resourcePath : rarFacade.getResourceLocations()) {
        raXml = project.getFolder(resourcePath).getFile(RA_XML);
        if (raXml.isAccessible()) {
          break;
        }
      }
    }
    return raXml;
  }
}
