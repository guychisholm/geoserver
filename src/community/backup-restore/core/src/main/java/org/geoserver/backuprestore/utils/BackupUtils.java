/* (c) 2016 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.backuprestore.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.vfs2.AllFileSelector;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSelectInfo;
import org.apache.commons.vfs2.FileSelector;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.VFS;
import org.codehaus.plexus.util.FileUtils;
import org.geoserver.platform.resource.Files;
import org.geoserver.platform.resource.Paths;
import org.geoserver.platform.resource.Resource;
import org.geoserver.platform.resource.Resource.Type;
import org.geoserver.platform.resource.Resources;
import org.geotools.util.logging.Logging;

/**
 * Utility to work with compressed files
 * 
 * Based on Importer {@link VFSWorker} by Gabriel Roldan
 * 
 * @author groldan
 * @autho Alessio Fabiani, GeoSolutions
 */
public class BackupUtils {

    private static final Logger LOGGER = Logging.getLogger(BackupUtils.class);

    public static Resource tmpDir() throws IOException {
        Resource root = Resources.fromPath(System.getProperty("java.io.tmpdir", "."));
        Resource directory = Resources.createRandom("tmp", "", root);

        do {
            FileUtils.forceDelete(directory.dir());
        } while (Resources.exists(directory));

        FileUtils.forceMkdir(directory.dir());

        return Files.asResource(directory.dir());
    }

    /**
     * Extracts the archive file {@code archiveFile} to {@code targetFolder}; both shall previously exist.
     *
     * @param archiveFile
     * @param targetFolder
     * @throws IOException
     */
    public static void extractTo(Resource archiveFile, Resource targetFolder) throws IOException {
        FileSystemManager manager = VFS.getManager();
        String sourceURI = resolveArchiveURI(archiveFile);

        FileObject source = manager.resolveFile(sourceURI);
        if (manager.canCreateFileSystem(source)) {
            source = manager.createFileSystem(source);
        }
        FileObject target = manager
                .createVirtualFileSystem(manager.resolveFile(targetFolder.dir().getAbsolutePath()));

        FileSelector selector = new AllFileSelector() {
            @Override
            public boolean includeFile(FileSelectInfo fileInfo) {
                LOGGER.fine("Uncompressing " + fileInfo.getFile().getName().getFriendlyURI());
                return true;
            }
        };
        target.copyFrom(source, selector);
        source.close();
        target.close();
        manager.closeFileSystem(source.getFileSystem());
    }

    /**
     * Compress {@code sourceFolder} to the archive file {@code archiveFile}; both shall previously exist.
     * 
     * @param sourceFolder
     * @param archiveFile
     * @throws IOException
     */
    public static void compressTo(Resource sourceFolder, Resource archiveFile) throws IOException {
        // See https://commons.apache.org/proper/commons-vfs/filesystems.html
        // for the supported filesystems

        FileSystemManager manager = VFS.getManager();

        FileObject sourceDir = manager
                .createVirtualFileSystem(manager.resolveFile(sourceFolder.dir().getAbsolutePath()));

        try {
            if ("zip".equalsIgnoreCase(FileUtils.getExtension(archiveFile.path()))) {
                // apache VFS does not support ZIP as writable FileSystem

                OutputStream fos = archiveFile.out();

                // Create access to zip.
                ZipOutputStream zos = new ZipOutputStream(fos);

                // add entry/-ies.
                for (FileObject sourceFile : sourceDir.getChildren()) {
                    writeEntry(zos, sourceFile, null);
                }

                // Close streams
                zos.flush();
                zos.close();
                fos.close();
            } else {
                // Create access to archive.
                FileObject zipFile = manager.resolveFile(resolveArchiveURI(archiveFile));
                zipFile.createFile();
                ZipOutputStream zos = new ZipOutputStream(zipFile.getContent().getOutputStream());

                // add entry/-ies.
                for (FileObject sourceFile : sourceDir.getChildren()) {
                    writeEntry(zos, sourceFile, null);
                }

                // Close streams
                zos.flush();
                zos.close();
                zipFile.close();
                manager.closeFileSystem(zipFile.getFileSystem());
            }
        } finally {
            manager.closeFileSystem(sourceDir.getFileSystem());
        }
    }

    /**
     * @param zos
     * @param sourceFile
     * @throws FileSystemException
     * @throws IOException
     */
    private static void writeEntry(ZipOutputStream zos, FileObject sourceFile, String baseDir)
            throws FileSystemException, IOException {
        if (sourceFile.getType() == FileType.FOLDER) {
            // add entry/-ies.
            for (FileObject file : sourceFile.getChildren()) {
                writeEntry(zos, file, Paths.path(baseDir, sourceFile.getName().getBaseName()));
            }
        } else {
            String fileName = (baseDir != null
                    ? Paths.path(baseDir, sourceFile.getName().getBaseName())
                    : sourceFile.getName().getBaseName());
            ZipEntry zipEntry = new ZipEntry(fileName);
            InputStream is = sourceFile.getContent().getInputStream();

            // Write to zip.
            byte[] buf = new byte[1024];
            zos.putNextEntry(zipEntry);
            for (int readNum; (readNum = is.read(buf)) != -1;) {
                zos.write(buf, 0, readNum);
            }
            zos.closeEntry();
            is.close();
        }
    }

    /**
     * 
     * @param archiveFile
     * @return
     */
    public static String resolveArchiveURI(final Resource archiveFile) {
        String archivePrefix = getArchiveURLProtocol(archiveFile);
        String absolutePath = archivePrefix + archiveFile.file().getAbsolutePath();
        return absolutePath;
    }

    /**
     * 
     * @param file
     * @return
     */
    public static String getArchiveURLProtocol(final Resource file) {
        if (file.getType() == Type.DIRECTORY) {
            return "file://";
        }
        String name = file.name().toLowerCase();
        if (name.endsWith(".zip") || name.endsWith(".kmz")) {
            return "zip://";
        }
        if (name.endsWith(".tar")) {
            return "tar://";
        }
        if (name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
            return "tgz://";
        }
        if (name.endsWith(".tbz2") || name.endsWith(".tar.bzip2") || name.endsWith(".tar.bz2")) {
            return "tbz2://";
        }
        if (name.endsWith(".gz")) {
            return "gz://";
        }
        if (name.endsWith(".bz2")) {
            return "bz2://";
        }
        if (name.endsWith(".jar")) {
            return "jar://";
        }
        return null;
    }

    /**
     * 
     * @param baseDir
     * @param subDir
     * @return
     */
    public static Resource dir(Resource baseDir, String subDir) {
        final Resource targetPath = Resources.fromPath(subDir, baseDir);
        return Resources.fromPath(
                Resources.directory(targetPath, !Resources.exists(targetPath)).getAbsolutePath());
    }
}