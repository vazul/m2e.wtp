/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.m2e.wtp;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.m2e.wtp.internal.preferences.MavenWtpPreferencesManagerImpl;
import org.eclipse.m2e.wtp.overlay.WebXmlChangeListener;
import org.eclipse.m2e.wtp.preferences.IMavenWtpPreferencesManager;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * m2e-wtp plugin
 *
 * @author Eugene Kuleshov
 */
public class MavenWtpPlugin extends AbstractUIPlugin {

  public static final String ID = "org.eclipse.m2e.wtp"; //$NON-NLS-1$
  
  private static MavenWtpPlugin instance;

  private IMavenWtpPreferencesManager preferenceManager; 
  
  private WebXmlChangeListener webXmlChangeListener;
  
  public IMavenWtpPreferencesManager getMavenWtpPreferencesManager() {
    return preferenceManager;
  }

  public MavenWtpPlugin() {
    instance = this;
  }
  
  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    
    this.preferenceManager = new MavenWtpPreferencesManagerImpl();

    for(IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
      if(project.isAccessible()) {
        project.setPersistentProperty(OverlayConfigurator.WEBXML_PATH, null);
        project.setPersistentProperty(OverlayConfigurator.WEBXML_TARGET_PATH, null);
      }
    }

    setupWebXmlChangeListener(this.preferenceManager.getWorkspacePreferences().isWarOverlaysUsesLinkedFolders());
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    super.stop(context);
    if(webXmlChangeListener != null) {
      ResourcesPlugin.getWorkspace().removeResourceChangeListener(webXmlChangeListener);
      webXmlChangeListener = null;
    }
  }
  
  public static MavenWtpPlugin getDefault() {
    return instance;
  }
  
  public void setupWebXmlChangeListener(boolean useLinkedFolders)
  {
    if(useLinkedFolders && webXmlChangeListener == null) {
      //start web xml change listener
      webXmlChangeListener = new WebXmlChangeListener();
      ResourcesPlugin.getWorkspace().addResourceChangeListener(webXmlChangeListener, IResourceChangeEvent.POST_CHANGE);
    } else if(!useLinkedFolders && webXmlChangeListener != null) {
      //stop web xml change listener
      ResourcesPlugin.getWorkspace().removeResourceChangeListener(webXmlChangeListener);
      webXmlChangeListener = null;
    }
  }

}
