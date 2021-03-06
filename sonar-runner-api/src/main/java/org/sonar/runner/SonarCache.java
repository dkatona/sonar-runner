/*
 * Sonar Runner - API
 * Copyright (C) 2011 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.runner;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * This class is responsible for managing Sonar batch file cache. You can put file into cache and
 * later try to retrieve them. MD5 is used to differentiate files (name is not secure as files may come
 * from different Sonar servers and have same name but be actually different, and same for SNAPSHOTs).
 * Default location of cache is 
 * @author Julien HENRY
 *
 */
public class SonarCache {

  private static final int TEMP_FILE_ATTEMPTS = 10000;

  private File cacheLocation;
  /**
   * Temporary directory where files should be stored before be inserted in the cache.
   * Having a temporary close to the final location (read on same FS) will assure
   * the move will be atomic.
   */
  private File tmpDir;

  private SonarCache(File cacheLocation) {
    this.cacheLocation = cacheLocation;
    tmpDir = new File(cacheLocation, "tmp");
    if (!cacheLocation.exists()) {
      Logs.debug("Creating cache directory: " + cacheLocation.getAbsolutePath());
      try {
        FileUtils.forceMkdir(cacheLocation);
      } catch (IOException e) {
        throw new RuntimeException("Unable to create cache directory " + cacheLocation.getAbsolutePath(), e);
      }
    }
  }

  public static class Builder {

    private File sonarUserHomeLocation;
    private File cacheLocation;

    public Builder(File sonarUserHomeLocation) {
      this.sonarUserHomeLocation = sonarUserHomeLocation;
    }

    public Builder setCacheLocation(File cacheLocation) {
      this.cacheLocation = cacheLocation;
      return this;
    }

    public SonarCache build() {
      if (cacheLocation == null) {
        return new SonarCache(new File(sonarUserHomeLocation, "cache"));
      }
      else {
        return new SonarCache(cacheLocation);
      }
    }
  }

  public static Builder create(File sonarUserHomeLocation) {
    if (sonarUserHomeLocation == null) {
      throw new RunnerException("Sonar user home directory should not be null");
    }
    return new Builder(sonarUserHomeLocation);
  }

  /**
   * Move the given file inside the cache. Return the MD5 of the cached file.
   * @param sourceFile
   * @throws IOException 
   */
  public String cacheFile(File sourceFile, String filename) throws IOException {
    Logs.debug("Trying to cache file " + sourceFile.getAbsolutePath() + " with filename " + filename);
    File tmpFileName = null;
    try {
      if (!sourceFile.getParentFile().equals(getTmpDir())) {
        // Provided file is not close to the cache so we will move it first in a temporary file (could be non atomic)
        tmpFileName = getTemporaryFile();
        FileUtils.moveFile(sourceFile, tmpFileName);
      }
      else {
        tmpFileName = sourceFile;
      }
      // Now compute the md5 to find the final destination
      String md5;
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(tmpFileName);
        md5 = DigestUtils.md5Hex(fis);
      } finally {
        IOUtils.closeQuietly(fis);
      }
      File finalDir = new File(cacheLocation, md5);
      File finalFileName = new File(finalDir, filename);
      // Try to create final destination folder
      FileUtils.forceMkdir(finalDir);
      // Now try to move the file from temporary folder to final location
      boolean rename = tmpFileName.renameTo(finalFileName);
      if (!rename) {
        // Check if the file was already in cache
        if (!finalFileName.exists()) {
          Logs.warn("Unable to rename " + tmpFileName.getAbsolutePath() + " to " + finalFileName.getAbsolutePath());
          Logs.warn("A copy/delete will be tempted but with no garantee of atomicity");
          FileUtils.moveFile(tmpFileName, finalFileName);
        }
      }
      Logs.debug("File cached at " + finalFileName.getAbsolutePath());
      return md5;
    } finally {
      FileUtils.deleteQuietly(tmpFileName);
    }

  }

  /**
   * Look for a file in the cache by its filename and md5 checksum. If the file is not
   * present then return null.
   */
  public File getFileFromCache(String filename, String md5) {
    File location = new File(new File(cacheLocation, md5), filename);
    Logs.debug("Looking for " + location.getAbsolutePath());
    if (location.exists()) {
      return location;
    }
    Logs.debug("No file found in the cache with name " + filename + " and checksum " + md5);
    return null;
  }

  /**
   * Return a temporary file that caller can use to store file content before
   * asking for caching it with {@link #cacheFile(File)}.
   * This is to avoid extra copy.
   * @return
   * @throws IOException 
   */
  public File getTemporaryFile() throws IOException {
    return createTempFile(getTmpDir());
  }

  /**
   * Create a temporary file in the given directory.
   * @param baseDir
   * @return
   * @throws IOException 
   */
  private static File createTempFile(File baseDir) throws IOException {
    String baseName = System.currentTimeMillis() + "-";

    for (int counter = 0; counter < TEMP_FILE_ATTEMPTS; counter++) {
      File tempFile = new File(baseDir, baseName + counter);
      if (tempFile.createNewFile()) {
        return tempFile;
      }
    }
    throw new IOException("Failed to create temporary file in " + baseDir.getAbsolutePath() + " within "
      + TEMP_FILE_ATTEMPTS + " attempts (tried "
      + baseName + "0 to " + baseName + (TEMP_FILE_ATTEMPTS - 1) + ')');
  }

  public File getTmpDir() {
    if (!tmpDir.exists()) {
      Logs.debug("Creating temporary cache directory: " + tmpDir.getAbsolutePath());
      try {
        FileUtils.forceMkdir(tmpDir);
      } catch (IOException e) {
        throw new RuntimeException("Unable to create temporary cache directory " + tmpDir.getAbsolutePath(), e);
      }
    }
    return tmpDir;
  }

  public File getCacheLocation() {
    return cacheLocation;
  }
}
