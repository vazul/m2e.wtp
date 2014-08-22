/*******************************************************************************
 * Copyright (c) 2011 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.m2e.wtp.overlay;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.wtp.JEEPackaging;
import org.eclipse.m2e.wtp.MavenWtpPlugin;
import org.eclipse.m2e.wtp.OverlayConfigurator;

/**
 * WebXmlChangeListener
 * 
 * @author varadi
 */
public class WebXmlChangeListener implements IResourceChangeListener {

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.core.resources.IResourceChangeListener#resourceChanged(org
	 * .eclipse.core.resources.IResourceChangeEvent)
	 */
	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		try {

			final HashSet<IMavenProjectFacade> projects = new HashSet<IMavenProjectFacade>();

			delta.accept(new IResourceDeltaVisitor() {
				@Override
				public boolean visit(IResourceDelta delta) throws CoreException {
					if (delta.getResource() instanceof IProject) {
						IProject project = (IProject) delta.getResource();
						IMavenProjectFacade facade = MavenPlugin
								.getMavenProjectRegistry().create(project,
										new NullProgressMonitor());
						if (facade != null
								&& JEEPackaging.getValue(facade.getPackaging()) == JEEPackaging.WAR) {
							projects.add(facade);
						}
						return false;
					}
					return true;
				}
			});

			final List<IMavenProjectFacade> needsToBeConfigured = new ArrayList<IMavenProjectFacade>(
					3);
			List<IPath> webXmlLocations = new ArrayList<IPath>(3);

			for (final IMavenProjectFacade facade : projects) {
				String webXmlPath = facade.getProject().getPersistentProperty(
						OverlayConfigurator.WEBXML_PATH);
				if (webXmlPath != null) {
					webXmlLocations.add(new Path(webXmlPath));
				} else {
					//check if any xml file has been changed with <web-app content
					delta.accept(new IResourceDeltaVisitor() {
						
						@Override
						public boolean visit(IResourceDelta delta) throws CoreException
						{
							IResource resource = delta.getResource();
							if (resource instanceof IFile && resource.getName().endsWith(".xml") && resource.getProject().equals(facade.getProject())) //$NON-NLS-1$
							{
								try
								{
									String xmlContent = IOUtil.toString(new FileReader(resource.getLocation().toFile()));
									if (xmlContent.indexOf("<web-app") >=0) //$NON-NLS-1$
									{
										needsToBeConfigured.add(facade);
										return false;
									}
								}
								catch (Exception ex)
								{
									//do nothing
								}
							}
							return true;
						}
					});
				}
			}

			for (IPath webXmlPath : webXmlLocations) {
				IResourceDelta webXmlChanged = delta.findMember(webXmlPath);
				if (webXmlChanged != null) {
					IProject project = webXmlChanged.getResource().getProject();
					String targetPath = project
							.getPersistentProperty(OverlayConfigurator.WEBXML_TARGET_PATH);
					final IFolder targetFolder = project.getFolder(targetPath);
					boolean shouldCopy = true;
					try {
						File targetFile = new File(targetFolder.getLocation()
								.toFile(), "web.xml"); //$NON-NLS-1$
						if (targetFile.exists()) {
							byte[] targetContent = IOUtil
									.toByteArray(new FileInputStream(targetFile));
							byte[] sourceContent = IOUtil
									.toByteArray(new FileInputStream(
											webXmlChanged.getResource()
													.getLocation().toFile()));
							shouldCopy = !Arrays.equals(sourceContent,
									targetContent);
						}
						if (shouldCopy) {
							FileUtils.copyFile(webXmlChanged.getResource()
									.getLocation().toFile(), targetFile);
						}
					} catch (IOException ex) {
						throw new CoreException(new Status(Status.ERROR,
								MavenWtpPlugin.ID,
								"Cannot copy web.xml to default source root from: " //$NON-NLS-1$
										+ webXmlPath, ex));
					}
					if (shouldCopy) {
						WorkspaceJob job = new WorkspaceJob("Refresh " //$NON-NLS-1$
								+ targetFolder.getFullPath().toPortableString()) {

							@Override
							public IStatus runInWorkspace(
									IProgressMonitor monitor)
									throws CoreException {
								targetFolder.refreshLocal(
										IResource.DEPTH_INFINITE, null);
								return Status.OK_STATUS;
							}
						};
						job.schedule();
					}
				}
			}

			if (needsToBeConfigured.size() > 0) {
				final IProjectConfigurationManager configurationManager = MavenPlugin
						.getProjectConfigurationManager();

				WorkspaceJob job = new WorkspaceJob("Updating maven projects ") { //$NON-NLS-1$

					@Override
					public IStatus runInWorkspace(IProgressMonitor monitor) {
						try {
							SubMonitor progress = SubMonitor.convert(monitor,
									"Updating Maven projects", 100); //$NON-NLS-1$
							SubMonitor subProgress = SubMonitor.convert(
									progress.newChild(5),
									needsToBeConfigured.size() * 100);
							for (IMavenProjectFacade facade : needsToBeConfigured) {
								if (progress.isCanceled()) {
									throw new OperationCanceledException();
								}
								IProject project = facade.getProject();
								subProgress
										.subTask("Updating configuration for " //$NON-NLS-1$
												+ project.getName());

								configurationManager
										.updateProjectConfiguration(project,
												subProgress);
							}

						} catch (CoreException ex) {
							return ex.getStatus();
						}
						return Status.OK_STATUS;
					}
				};
				job.setRule(configurationManager.getRule());
				job.schedule();
			}
		} catch (Exception ex) {
			// TODO: handle exception
		}
	}

}
