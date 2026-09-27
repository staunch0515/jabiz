package com.jabiz.runtime.file;

/** Permission codes of the platform's files (docs/design/14-files.md section 2); policies add their own. */
public final class FilePermissions {

    /** Reading any file's metadata and content, whatever its policy; also the SysFile dataset. */
    public static final String READ = "file.read";
    /** Declared by the SysFile dataset and the registering process, which only the platform writes through. */
    public static final String WRITE = "file.write";
    /** Deleting a file that nothing refers to any more (FILE_DELETE), and purging orphans. */
    public static final String DELETE = "file.delete";

    private FilePermissions() {}
}
