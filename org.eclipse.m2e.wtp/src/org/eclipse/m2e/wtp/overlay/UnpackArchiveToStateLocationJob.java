/*******************************************************************************
 * Copyright (c) 2011 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.m2e.wtp.overlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.codehaus.plexus.util.FileUtils;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.m2e.wtp.MavenWtpPlugin;

/**
 * This job unpacks the war file into this plugin's state location under folder "exploded-wars".
 * During unpacking, the WEB-INF/web.xml and WEB-INF/lib content won't be exploded.
 *
 * @author varadi
 */
public class UnpackArchiveToStateLocationJob extends WorkspaceJob {

  private final File unpackFolder;

  private final File archive;

  private final IFolder folderToRefresh;

  private static class Rule implements ISchedulingRule {

    private final File path;

    public Rule(final File path) {
      this.path = path;
    }

    @Override
	public boolean contains(final ISchedulingRule rule) {
      if(rule instanceof Rule) {
        return ((Rule) rule).path.equals(path);
      }
      return false;
    }

    /* (non-Javadoc)
     * @see org.eclipse.core.runtime.jobs.ISchedulingRule#isConflicting(org.eclipse.core.runtime.jobs.ISchedulingRule)
     */
    @Override
	public boolean isConflicting(final ISchedulingRule rule) {
      if(rule instanceof Rule) {
        return ((Rule) rule).path.equals(path);
      }
      return false;
    }

  }

  public UnpackArchiveToStateLocationJob(final String name, final File archive, final File unpackFolder, final IFolder folderToRefresh) {
    super(name);
    assert unpackFolder != null;
    assert archive != null && archive.exists() && archive.canRead();
    this.unpackFolder = unpackFolder;
    this.archive = archive;
    setRule(new Rule(unpackFolder));
    this.folderToRefresh = folderToRefresh;
  }

  @Override
  public IStatus runInWorkspace(final IProgressMonitor monitor) throws CoreException {
    try {
      if(unpackFolder.exists()) {
        FileUtils.deleteDirectory(unpackFolder);
      }
      unpackFolder.mkdirs();
      unpack(archive, unpackFolder, monitor);
    } catch(final IOException e) {
      return new Status(IStatus.ERROR, MavenWtpPlugin.ID, "Error unpacking " + archive.getName(), e); //$NON-NLS-1$
    }

    //will run in scheduling rule of parent of unpackfolder, so should be run in a different job
    new WorkspaceJob(folderToRefresh.getLocation().toString() + " refresher") { //$NON-NLS-1$

      @Override
      public IStatus runInWorkspace(final IProgressMonitor monitor) throws CoreException {
        final SubMonitor subMonitor = SubMonitor.convert(monitor);
        subMonitor.beginTask("Refreshing exploded war: " + folderToRefresh.getLocation(), 100); //$NON-NLS-1$
        folderToRefresh.refreshLocal(IFolder.DEPTH_INFINITE, subMonitor.newChild(100));
        subMonitor.done();
        return Status.OK_STATUS;
      }
    }.schedule();

    return Status.OK_STATUS;
  }

  private void unpack(final File sourceFile, final File targetDir, final IProgressMonitor monitor) {
	ZipFile zipFile = null;
    try {
      zipFile = new ZipFile(sourceFile);
      monitor.beginTask("Unzipping " + sourceFile + " to " + targetDir, zipFile.size()); //$NON-NLS-1$ //$NON-NLS-2$
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while(entries.hasMoreElements()) {
        final ZipEntry zipEntry = entries.nextElement();
        if(shouldExplode(zipEntry)) {
          final File file = new File(targetDir, zipEntry.getName());

          if(!zipEntry.isDirectory()) {

            final File parentFile = file.getParentFile();
            if(null != parentFile && !parentFile.exists()) {
              parentFile.mkdirs();
            }

            InputStream is = null;
            OutputStream os = null;

            try {
              is = zipFile.getInputStream(zipEntry);
              os = new FileOutputStream(file);

              final byte[] buffer = new byte[1024 * 4];
              while(true) {
                final int len = is.read(buffer);
                if(len < 0) {
                  break;
                }
                os.write(buffer, 0, len);
              }
            } finally {
              if(is != null) {
                is.close();
              }
              if(os != null) {
                os.close();
              }
            }
          }
        }

        monitor.worked(1);

        if(monitor.isCanceled()) {
          throw new InterruptedException(" unzipping " + sourceFile.getAbsolutePath() + " to " //$NON-NLS-1$ //$NON-NLS-2$
              + targetDir.getAbsolutePath() + " was interrupted"); //$NON-NLS-1$
        }
      }
    } catch(final Exception ex) {
      throw new RuntimeException("Cannot unpack zip file: " + sourceFile, ex); //$NON-NLS-1$
    }
    finally {
      if (zipFile != null) {
        try {
			zipFile.close();
		} catch (IOException ex) {
			MavenWtpPlugin.getDefault().getLog().log(new Status(IStatus.WARNING, MavenWtpPlugin.ID, "Cannot close zip file: " + zipFile.getName())); //$NON-NLS-1$
		}
      }
    }
    targetDir.setLastModified(sourceFile.lastModified());
    monitor.done();
  }

  private boolean shouldExplode(ZipEntry zipEntry) {
    if(zipEntry.getName().equals("WEB-INF/web.xml")) { //$NON-NLS-1$
      return false;
    }
    if(zipEntry.getName().startsWith("WEB-INF/lib")) { //$NON-NLS-1$
      return false;
    }
    return true;
  }

}
