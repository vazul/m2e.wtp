/*******************************************************************************
 * Copyright (c) 2011 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.m2e.wtp.overlay;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.plexus.util.FileUtils;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.m2e.wtp.MavenWtpPlugin;

/**
 * ExplodedWarCleaner to cleanup unused exploded war content from plugin's state location.
 *
 * @author varadi
 */
public class ExplodedWarCleaner extends WorkspaceJob {
  public static final long DELAY = 10000; //10 seconds

  private static ExplodedWarCleaner scheduledExplodedWarCleaner = null;

  /**
   * @param name
   */
  public ExplodedWarCleaner(final String name) {
    super(name);
    setPriority(Job.DECORATE);
    setSystem(true);
  }


  public static void scheduleClean() {
    if(scheduledExplodedWarCleaner != null) {
      scheduledExplodedWarCleaner.cancel();
    }
    scheduledExplodedWarCleaner = new ExplodedWarCleaner("Clean up unused exploded war artifacts"); //$NON-NLS-1$
    scheduledExplodedWarCleaner.schedule(DELAY);
  }

  @Override
  public IStatus runInWorkspace(final IProgressMonitor monitor) throws CoreException {
    try {
      final Set<String> folders = getExplodedFolders();
      final File explodedFolder = new File(MavenWtpPlugin.getDefault().getStateLocation().toString() + File.pathSeparator + LinkedOverlaysConstants.EXPLODED_WAR_FOLDER);
      if(!explodedFolder.exists()) {
        return Status.OK_STATUS;
      }
      final Set<String> toRemove = new HashSet<String>();
      for(final String folder : explodedFolder.list()) {
        if(!folders.contains(folder)) {
          toRemove.add(folder);
        }
      }
      monitor.setTaskName("Clean up..."); //$NON-NLS-1$
      monitor.beginTask("Deleting unused exploded war artifacts", toRemove.size()); //$NON-NLS-1$

      final String explodedContainer = MavenWtpPlugin.getDefault().getStateLocation().toString() + File.pathSeparator + LinkedOverlaysConstants.EXPLODED_WAR_FOLDER + File.pathSeparator;
      for(final String folder : toRemove) {
        if(monitor.isCanceled()) {
          return Status.CANCEL_STATUS;
        }
        monitor.subTask("Deleting " + folder + "...");  //$NON-NLS-1$//$NON-NLS-2$
        try {
          FileUtils.deleteDirectory(explodedContainer + folder);
        } catch(final Exception ex) {
        }
        monitor.worked(1);
      }
    } finally {
      scheduledExplodedWarCleaner = null;
    }
    return Status.OK_STATUS;
  }

  /**
   * @return
   * @throws CoreException
   */
  private Set<String> getExplodedFolders() throws CoreException {
    final Set<String> result = new HashSet<String>();
    final IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
    for(final IProject project : projects) {
      final IFolder overlaysFolder = project.getFolder(".overlays"); //$NON-NLS-1$
      if(overlaysFolder.exists()) {
        final IResource[] overlays = overlaysFolder.members();
        for(final IResource overlay : overlays) {
          if(overlay instanceof IFolder) {
            final IFolder folder = (IFolder) overlay;
            if(folder.isLinked() && !folder.isVirtual()) {
              IPath rawLocation = folder.getRawLocation();
              if(rawLocation != null) {
                result.add(rawLocation.lastSegment());
              }
            }
          }
        }
      }
    }
    return result;
  }
}

