package java.nio.file

import java.lang.Iterable
import java.io.{
  BufferedReader,
  BufferedWriter,
  File,
  FileOutputStream,
  IOException,
  InputStream,
  InputStreamReader,
  OutputStream,
  OutputStreamWriter,
  UncheckedIOException
}
import java.nio.file.attribute._
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.channels.{FileChannel, SeekableByteChannel}
import java.util.function.BiPredicate
import java.util.{
  EnumSet,
  HashMap,
  HashSet,
  Iterator,
  LinkedList,
  List,
  Map,
  Set
}
import java.util.stream.{Stream, WrappedScalaStream}
import scalanative.unsigned._
import scalanative.unsafe._
import scalanative.libc._
import scalanative.posix.{dirent, fcntl, limits, unistd}
import dirent._
import java.nio.file.StandardCopyOption.{COPY_ATTRIBUTES, REPLACE_EXISTING}
import scalanative.nio.fs.unix.UnixException
import scalanative.posix.sys.stat
import scalanative.windows._
import scalanative.windows.WinBaseApi._
import scalanative.windows.WinBaseApiExt._
import scalanative.windows.FileApiExt._
import scalanative.windows.ErrorHandlingApi._
import scalanative.windows.winnt.AccessRights._
import java.util.WindowsHelperMethods._
import scalanative.nio.fs.FileHelpers
import scalanative.compat.StreamsCompat._
import scalanative.meta.LinktimeInfo.isWindows
import scala.collection.immutable.{Map => SMap, Set => SSet}
import java.io.FileNotFoundException

object Files {

  private val `1U` = 1.toUInt

  // def getFileStore(path: Path): FileStore
  // def probeContentType(path: Path): String

  def copy(in: InputStream, target: Path, options: Array[CopyOption]): Long = {
    val replaceExisting =
      if (options.isEmpty) false
      else if (options.length == 1 && options(0) == REPLACE_EXISTING)
        true
      else throw new UnsupportedOperationException()

    val targetFile = target.toFile()

    val out =
      if (!targetFile.exists() || (targetFile.isFile() && replaceExisting)) {
        new FileOutputStream(targetFile, append = false)
      } else if (targetFile.isDirectory() &&
          targetFile.list().isEmpty &&
          replaceExisting) {
        if (!targetFile.delete()) throw new IOException()
        new FileOutputStream(targetFile, append = false)
      } else if (targetFile.isDirectory() &&
          !targetFile.list().isEmpty &&
          replaceExisting) {
        throw new DirectoryNotEmptyException(targetFile.getAbsolutePath())
      } else {
        throw new FileAlreadyExistsException(targetFile.getAbsolutePath())
      }

    try copy(in, out)
    finally out.close()
  }

  def copy(source: Path, out: OutputStream): Long = {
    val in = newInputStream(source, Array.empty)
    copy(in, out)
  }

  def copy(source: Path, target: Path, options: Array[CopyOption]): Path = {
    val linkOpts = Array(LinkOption.NOFOLLOW_LINKS)
    val attrsCls =
      if (isWindows) classOf[DosFileAttributes]
      else classOf[PosixFileAttributes]

    val attrs = Files.readAttributes(source, attrsCls, linkOpts)
    if (attrs.isSymbolicLink())
      throw new IOException(
        s"Unsupported operation: copy symbolic link $source to $target"
      )

    val targetExists = exists(target, linkOpts)
    if (targetExists && !options.contains(REPLACE_EXISTING))
      throw new FileAlreadyExistsException(target.toString)

    if (isDirectory(source, Array.empty)) {
      createDirectory(target, Array.empty)
    } else {
      val in = newInputStream(source, Array.empty)
      try copy(in, target, options.filter(_ == REPLACE_EXISTING))
      finally in.close()
    }

    if (options.contains(COPY_ATTRIBUTES)) {
      val attrViewCls =
        if (isWindows) classOf[DosFileAttributeView]
        else classOf[PosixFileAttributeView]
      val newAttrView = getFileAttributeView(target, attrViewCls, linkOpts)

      (attrs, newAttrView) match {
        case (
              attrs: PosixFileAttributes,
              newAttrView: PosixFileAttributeView
            ) =>
          newAttrView.setGroup(attrs.group())
          newAttrView.setOwner(attrs.owner())
          newAttrView.setPermissions(attrs.permissions())
        case (attrs: DosFileAttributes, newAttrView: DosFileAttributeView) =>
          newAttrView.setArchive(attrs.isArchive())
          newAttrView.setHidden(attrs.isHidden())
          newAttrView.setReadOnly(attrs.isReadOnly())
          newAttrView.setSystem(attrs.isSystem())
      }
      newAttrView.setTimes(
        attrs.lastModifiedTime(),
        attrs.lastAccessTime(),
        attrs.creationTime()
      )
    }
    target
  }

  private def copy(in: InputStream, out: OutputStream): Long = {
    var written: Long = 0L
    var value: Int = 0

    while ({ value = in.read(); value != -1 }) {
      out.write(value)
      written += 1
    }

    written
  }

  def createDirectories(dir: Path, attrs: Array[FileAttribute[_]]): Path =
    if (exists(dir, Array.empty) && !isDirectory(dir, Array.empty))
      throw new FileAlreadyExistsException(dir.toString)
    else if (exists(dir, Array.empty)) dir
    else {
      val parent = dir.getParent()
      if (parent != null) createDirectories(parent, attrs)
      createDirectory(dir, attrs)
      dir
    }

  def createDirectory(dir: Path, attrs: Array[FileAttribute[_]]): Path =
    if (exists(dir, Array.empty)) {
      if (!isDirectory(dir, Array.empty)) {
        throw new FileAlreadyExistsException(dir.toString)
      } else if (list(dir).iterator().hasNext()) {
        throw new DirectoryNotEmptyException(dir.toString)
      }
      dir
    } else if (dir.toFile().mkdir()) {
      setAttributes(dir, attrs)
      dir
    } else {
      throw new IOException()
    }

  def createFile(path: Path, attrs: Array[FileAttribute[_]]): Path =
    if (exists(path, Array.empty))
      throw new FileAlreadyExistsException(path.toString)
    else if (FileHelpers.createNewFile(path.toString)) {
      setAttributes(path, attrs)
      path
    } else {
      throw new IOException()
    }

  def createLink(link: Path, existing: Path): Path = {
    def tryCreateHardLink() = Zone { implicit z =>
      if (isWindows)
        CreateHardLinkW(
          toCWideStringUTF16LE(link.toString),
          toCWideStringUTF16LE(existing.toString),
          securityAttributes = null
        )
      else
        unistd.link(
          toCString(existing.toString()),
          toCString(link.toString())
        ) == 0
    }
    if (exists(link, Array.empty)) {
      throw new FileAlreadyExistsException(link.toString)
    } else if (tryCreateHardLink()) {
      link
    } else {
      throw new IOException("Cannot create link")
    }
  }

  def createSymbolicLink(
      link: Path,
      target: Path,
      attrs: Array[FileAttribute[_]]
  ): Path = {

    def tryCreateLink() = Zone { implicit z =>
      if (isWindows) {
        import WinBaseApiExt._
        val targetFilename = toCWideStringUTF16LE(target.toString())
        val linkFilename = toCWideStringUTF16LE(link.toString())
        val flags =
          if (target.toFile().isFile()) SYMBOLIC_LINK_FLAG_FILE
          else SYMBOLIC_LINK_FLAG_DIRECTORY
        val created =
          CreateSymbolicLinkW(
            symlinkFileName = linkFilename,
            targetFileName = targetFilename,
            flags = flags
          )
        val ERROR_PRIVILEGE_NOT_HELD = 1314.toUInt
        // On Windows creating soft link is possible only with admin privileges
        if (!created &&
            GetLastError() == ERROR_PRIVILEGE_NOT_HELD) {
          // If develop mode is enabled we can create unprivileged symlinks
          CreateSymbolicLinkW(
            symlinkFileName = linkFilename,
            targetFileName = targetFilename,
            flags = flags | SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE
          ) || {
            throw new SecurityException(
              "Creating symbolic link requires admin privileges"
            )
          }
        } else created
      } else {
        val targetFilename = toCString(target.toString())
        val linkFilename = toCString(link.toString())
        unistd.symlink(targetFilename, linkFilename) == 0
      }
    }

    if (exists(link, Array.empty)) {
      throw new FileAlreadyExistsException(target.toString)
    } else if (tryCreateLink()) {
      setAttributes(link, attrs)
      link
    } else {
      throw new IOException("Cannot create symbolic link")
    }
  }

  private def createTempDirectory(
      dir: File,
      prefix: String,
      attrs: Array[FileAttribute[_]]
  ): Path = {
    val p = if (prefix == null) "" else prefix
    val temp = FileHelpers.createTempFile(p, "", dir, minLength = false)
    if (temp.delete() && temp.mkdir()) {
      val tempPath = temp.toPath()
      setAttributes(tempPath, attrs)
      tempPath
    } else {
      throw new IOException()
    }
  }

  def createTempDirectory(
      dir: Path,
      prefix: String,
      attrs: Array[FileAttribute[_]]
  ): Path =
    createTempDirectory(dir.toFile(), prefix, attrs)

  def createTempDirectory(
      prefix: String,
      attrs: Array[FileAttribute[_]]
  ): Path =
    createTempDirectory(null: File, prefix, attrs)

  private def createTempFile(
      dir: File,
      prefix: String,
      suffix: String,
      attrs: Array[FileAttribute[_]]
  ): Path = {
    val p = if (prefix == null) "" else prefix
    val temp = FileHelpers.createTempFile(p, suffix, dir, minLength = false)
    val tempPath = temp.toPath()
    setAttributes(tempPath, attrs)
    tempPath
  }

  def createTempFile(
      dir: Path,
      prefix: String,
      suffix: String,
      attrs: Array[FileAttribute[_]]
  ): Path =
    createTempFile(dir.toFile(), prefix, suffix, attrs)

  def createTempFile(
      prefix: String,
      suffix: String,
      attrs: Array[FileAttribute[_]]
  ): Path =
    createTempFile(null: File, prefix, suffix, attrs)

  def delete(path: Path): Unit = {
    if (!exists(path, Array.empty)) {
      throw new NoSuchFileException(path.toString)
    } else {
      if (path.toFile().delete()) ()
      else throw new IOException(s"Failed to remove $path")
    }
  }

  def deleteIfExists(path: Path): Boolean =
    try {
      delete(path); true
    } catch { case _: NoSuchFileException => false }

  def exists(path: Path, options: Array[LinkOption]): Boolean = {
    def fileExists = path.toFile().exists()
    def noFollowLinks = options.contains(LinkOption.NOFOLLOW_LINKS)

    fileExists || (noFollowLinks && isSymbolicLink(path))
  }

  def find(
      start: Path,
      maxDepth: Int,
      matcher: BiPredicate[Path, BasicFileAttributes],
      options: Array[FileVisitOption]
  ): Stream[Path] = {
    val nofollow = Array(LinkOption.NOFOLLOW_LINKS)
    val stream = walk(start, maxDepth, 0, options, SSet.empty).filter { p =>
      val brokenSymLink =
        if (isSymbolicLink(p)) {
          val target = readSymbolicLink(p)
          val targetExists = exists(target, nofollow)
          !targetExists
        } else false
      val linkOpts =
        if (!brokenSymLink) linkOptsFromFileVisitOpts(options) else nofollow
      val attributes =
        getFileAttributeView(p, classOf[BasicFileAttributeView], linkOpts)
          .readAttributes()

      matcher.test(p, attributes)
    }
    new WrappedScalaStream(stream, None)
  }

  def getAttribute(
      path: Path,
      attribute: String,
      options: Array[LinkOption]
  ): Object = {
    val sepIndex = attribute.indexOf(":")
    val (viewName, attrName) =
      if (sepIndex == -1) ("basic", attribute)
      else
        (
          attribute.substring(0, sepIndex),
          attribute.substring(sepIndex + 1, attribute.length)
        )
    val viewClass = viewNamesToClasses
      .get(viewName)
      .getOrElse(throw new UnsupportedOperationException())
    val view = getFileAttributeView(path, viewClass, options)
    view.getAttribute(attrName)
  }

  def getFileAttributeView[V <: FileAttributeView](
      path: Path,
      tpe: Class[V],
      options: Array[LinkOption]
  ): V =
    path.getFileSystem().provider().getFileAttributeView(path, tpe, options)

  def getLastModifiedTime(path: Path, options: Array[LinkOption]): FileTime = {
    val realPath = path.toRealPath(options)
    val attributes =
      getFileAttributeView(path, classOf[BasicFileAttributeView], options)
        .readAttributes()
    attributes.lastModifiedTime()
  }

  def getOwner(path: Path, options: Array[LinkOption]): UserPrincipal = {
    val view =
      getFileAttributeView(path, classOf[FileOwnerAttributeView], options)
    view.getOwner()
  }

  def getPosixFilePermissions(
      path: Path,
      options: Array[LinkOption]
  ): Set[PosixFilePermission] =
    getAttribute(path, "posix:permissions", options)
      .asInstanceOf[Set[PosixFilePermission]]

  def isDirectory(path: Path, options: Array[LinkOption]): Boolean = {
    def notALink =
      if (options.contains(LinkOption.NOFOLLOW_LINKS)) !isSymbolicLink(path)
      else true
    exists(path, options) && notALink && path.toFile().isDirectory()
  }

  def isExecutable(path: Path): Boolean =
    path.toFile().canExecute()

  def isHidden(path: Path): Boolean =
    path.toFile().isHidden()

  def isReadable(path: Path): Boolean =
    path.toFile().canRead()

  def isRegularFile(path: Path, options: Array[LinkOption]): Boolean = {
    if (isWindows) {
      getAttribute(path, "basic:isRegularFile", options).asInstanceOf[Boolean]
    } else
      Zone { implicit z =>
        val buf = alloc[stat.stat]
        val err =
          if (options.contains(LinkOption.NOFOLLOW_LINKS)) {
            stat.lstat(toCString(path.toFile().getPath()), buf)
          } else {
            stat.stat(toCString(path.toFile().getPath()), buf)
          }
        if (err == 0) stat.S_ISREG(buf._13) == 1
        else false
      }
  }

  def isSameFile(path: Path, path2: Path): Boolean =
    path.toFile().getCanonicalPath() == path2.toFile().getCanonicalPath()

  def isSymbolicLink(path: Path): Boolean = Zone { implicit z =>
    if (isWindows) {
      val filename = toCWideStringUTF16LE(path.toFile().getPath())
      val attrs = FileApi.GetFileAttributesW(filename)
      val exists = attrs != INVALID_FILE_ATTRIBUTES
      def isReparsePoint = (attrs & FILE_ATTRIBUTE_REPARSE_POINT) != 0.toUInt
      exists & isReparsePoint
    } else {
      val filename = toCString(path.toFile().getPath())
      val buf = alloc[stat.stat]
      if (stat.lstat(filename, buf) == 0) {
        stat.S_ISLNK(buf._13) == 1
      } else {
        false
      }
    }
  }

  def isWritable(path: Path): Boolean =
    path.toFile().canWrite()

  def lines(path: Path): Stream[String] =
    lines(path, StandardCharsets.UTF_8)

  def lines(path: Path, cs: Charset): Stream[String] =
    newBufferedReader(path, cs).lines(true)

  def list(dir: Path): Stream[Path] =
    new WrappedScalaStream(
      FileHelpers.list(dir.toString, (n, _) => dir.resolve(n)).toScalaStream,
      None
    )

  def move(source: Path, target: Path, options: Array[CopyOption]): Path = {
    lazy val replaceExisting = options.contains(REPLACE_EXISTING)

    if (!exists(source.toAbsolutePath(), Array.empty)) {
      throw new NoSuchFileException(source.toString)
    } else if (!exists(
          target.toAbsolutePath(),
          Array.empty
        ) || replaceExisting) {
      moveImpl(source, target, replaceExisting)
    } else {
      throw new FileAlreadyExistsException(target.toString)
    }
    target
  }

  private def moveImpl(
      source: Path,
      target: Path,
      replaceExisting: => Boolean
  ) =
    Zone { implicit z =>
      val sourceAbs = source.toAbsolutePath().toString
      val targetAbs = target.toAbsolutePath().toString
      // We cannot replace directory, it needs to be removed first
      if (replaceExisting && target.toFile().isDirectory()) {
        //todo delete children
        Files.delete(target)
      }
      if (isWindows) {
        val sourceCString = toCWideStringUTF16LE(sourceAbs)
        val targetCString = toCWideStringUTF16LE(targetAbs)

        // stdio.rename on Windows does not replace existing file
        val flags = {
          val replace =
            if (replaceExisting) MOVEFILE_REPLACE_EXISTING else 0.toUInt
          MOVEFILE_COPY_ALLOWED | //Allow coping betwen volumes
            MOVEFILE_WRITE_THROUGH | //Block until actually moved
            replace
        }
        if (!MoveFileExW(sourceCString, targetCString, flags)) {
          GetLastError() match {
            case ErrorCodes.ERROR_SUCCESS => ()
            case _ => throw WindowsException.onPath(target.toString())
          }
        }
      } else {
        val sourceCString = toCString(sourceAbs)
        val targetCString = toCString(targetAbs)
        if (stdio.rename(sourceCString, targetCString) != 0) {
          throw UnixException(target.toString, errno.errno)
        }
      }
    }

  def newBufferedReader(path: Path): BufferedReader =
    newBufferedReader(path, StandardCharsets.UTF_8)

  def newBufferedReader(path: Path, cs: Charset): BufferedReader =
    new BufferedReader(
      new InputStreamReader(newInputStream(path, Array.empty), cs)
    )

  def newBufferedWriter(
      path: Path,
      cs: Charset,
      options: Array[OpenOption]
  ): BufferedWriter = {
    new BufferedWriter(
      new OutputStreamWriter(newOutputStream(path, options), cs)
    )
  }

  def newBufferedWriter(
      path: Path,
      options: Array[OpenOption]
  ): BufferedWriter =
    newBufferedWriter(path, StandardCharsets.UTF_8, options)

  def newByteChannel(
      path: Path,
      _options: Array[OpenOption]
  ): SeekableByteChannel = {
    val options = new HashSet[OpenOption]()
    _options.foreach(options.add _)
    newByteChannel(path, options, Array.empty)
  }

  def newByteChannel(
      path: Path,
      options: Set[_ <: OpenOption],
      attrs: Array[FileAttribute[_]]
  ): SeekableByteChannel =
    path.getFileSystem().provider().newByteChannel(path, options, attrs)

  def newDirectoryStream(dir: Path): DirectoryStream[Path] = {
    val filter = new DirectoryStream.Filter[Path] {
      override def accept(p: Path): Boolean = true
    }
    newDirectoryStream(dir, filter)
  }

  def newDirectoryStream(
      dir: Path,
      filter: DirectoryStream.Filter[_ >: Path]
  ): DirectoryStream[Path] =
    dir.getFileSystem().provider().newDirectoryStream(dir, filter)

  def newDirectoryStream(dir: Path, glob: String): DirectoryStream[Path] = {
    val filter = new DirectoryStream.Filter[Path] {
      private val matcher =
        FileSystems.getDefault().getPathMatcher("glob:" + glob)
      override def accept(p: Path): Boolean = matcher.matches(p)
    }
    newDirectoryStream(dir, filter)
  }

  def newInputStream(path: Path, options: Array[OpenOption]): InputStream =
    path.getFileSystem().provider().newInputStream(path, options)

  def newOutputStream(path: Path, options: Array[OpenOption]): OutputStream =
    path.getFileSystem().provider().newOutputStream(path, options)

  def notExists(path: Path, options: Array[LinkOption]): Boolean =
    !exists(path, options)

  def readAllBytes(path: Path): Array[Byte] = Zone { implicit z =>
    val pathSize: Long = size(path)
    if (!pathSize.isValidInt) {
      throw new OutOfMemoryError("Required array size too large")
    }
    val len = pathSize.toInt
    val bytes = scala.scalanative.runtime.ByteArray.alloc(len)

    if (isWindows) {
      val bytesRead = stackalloc[DWord]()

      withFileOpen(
        path.toString,
        access = FILE_GENERIC_READ,
        shareMode = FILE_SHARE_READ
      ) { handle =>
        if (!FileApi.ReadFile(
              handle,
              bytes.at(0),
              pathSize.toUInt,
              bytesRead,
              null
            )) {
          throw WindowsException.onPath(path.toString())
        }
      }
    } else {
      val pathCString = toCString(path.toString)
      val fd = fcntl.open(pathCString, fcntl.O_RDONLY, 0.toUInt)
      try {
        var offset = 0
        var read = 0
        while ({
          read = unistd.read(fd, bytes.at(offset), (len - offset).toUInt);
          read != -1 && (offset + read) < len
        }) {
          offset += read
        }
        if (read == -1) throw UnixException(path.toString, errno.errno)
      } finally {
        unistd.close(fd)
      }
    }
    bytes.asInstanceOf[Array[Byte]]
  }

  def readAllLines(path: Path): List[String] =
    readAllLines(path, StandardCharsets.UTF_8)

  def readAllLines(path: Path, cs: Charset): List[String] = {
    val list = new LinkedList[String]()
    val reader = newBufferedReader(path, cs)
    try {
      val lines = reader.lines().iterator()
      while (lines.hasNext()) {
        list.add(lines.next())
      }
    } finally {
      reader.close()
    }
    list
  }

  def readAttributes[A <: BasicFileAttributes](
      path: Path,
      tpe: Class[A],
      options: Array[LinkOption]
  ): A = {
    val viewClass = attributesClassesToViews
      .get(tpe)
      .getOrElse(throw new UnsupportedOperationException())
    val view = getFileAttributeView(path, viewClass, options)
    view.readAttributes().asInstanceOf[A]
  }

  def readAttributes(
      path: Path,
      attributes: String,
      options: Array[LinkOption]
  ): Map[String, Object] = {
    val parts = attributes.split(":")
    val (viewName, atts) =
      if (parts.length == 1) ("basic", parts(0))
      else (parts(0), parts(1))

    if (atts == "*") {
      val viewClass = viewNamesToClasses
        .get(viewName)
        .getOrElse(throw new UnsupportedOperationException())
      getFileAttributeView(path, viewClass, options).asMap
    } else {
      val attrs = atts.split(",")
      val map = new HashMap[String, Object]()
      attrs.foreach { att =>
        val value = getAttribute(path, viewName + ":" + att, options)
        if (value != null)
          map.put(att, value)
      }
      map
    }
  }

  def readSymbolicLink(link: Path): Path =
    if (!isSymbolicLink(link)) {
      throw new NotLinkException(link.toString)
    } else
      Zone { implicit z =>
        val name = if (isWindows) {
          withFileOpen(
            link.toString,
            access = FILE_GENERIC_READ
          ) { handle =>
            val bufferSize = FileApiExt.MAX_PATH
            val buffer: Ptr[WChar] = alloc[WChar](bufferSize)
            val pathSize =
              FileApi.GetFinalPathNameByHandleW(
                handle,
                buffer,
                bufferSize,
                FileApiExt.FILE_NAME_NORMALIZED
              )
            if (pathSize > bufferSize) {
              throw WindowsException(
                "Target path size of link was greater then max allowed size"
              )
            }
            fromCWideString(buffer, StandardCharsets.UTF_16LE)
          }
        } else {
          val buf: CString = alloc[Byte](limits.PATH_MAX.toUInt)
          if (unistd.readlink(
                toCString(link.toString),
                buf,
                limits.PATH_MAX - `1U`
              ) == -1) {
            throw UnixException(link.toString, errno.errno)
          }
          fromCString(buf)
        }
        Paths.get(name, Array.empty)
      }

  def setAttribute(
      path: Path,
      attribute: String,
      value: AnyRef,
      options: Array[LinkOption]
  ): Path = {
    val sepIndex = attribute.indexOf(":")
    val (viewName, attrName) =
      if (sepIndex == -1) ("basic", attribute)
      else
        (
          attribute.substring(0, sepIndex),
          attribute.substring(sepIndex + 1, attribute.length)
        )
    val viewClass = viewNamesToClasses
      .get(viewName)
      .getOrElse(throw new UnsupportedOperationException())
    val view = getFileAttributeView(path, viewClass, options)
    view.setAttribute(attrName, value)
    path
  }

  def setLastModifiedTime(path: Path, time: FileTime): Path = {
    val view =
      getFileAttributeView(path, classOf[BasicFileAttributeView], Array.empty)
    view.setTimes(time, null, null)
    path
  }

  def setOwner(path: Path, owner: UserPrincipal): Path = {
    val view =
      getFileAttributeView(path, classOf[FileOwnerAttributeView], Array.empty)
    view.setOwner(owner)
    path
  }

  def setPosixFilePermissions(
      path: Path,
      perms: Set[PosixFilePermission]
  ): Path = {
    val view =
      getFileAttributeView(path, classOf[PosixFileAttributeView], Array.empty)
    view.setPermissions(perms)
    path
  }

  def size(path: Path): Long =
    getAttribute(path, "basic:size", Array.empty).asInstanceOf[Long]

  def walk(start: Path, options: Array[FileVisitOption]): Stream[Path] =
    walk(start, Int.MaxValue, options)

  def walk(
      start: Path,
      maxDepth: Int,
      options: Array[FileVisitOption]
  ): Stream[Path] =
    new WrappedScalaStream(walk(start, maxDepth, 0, options, Set(start)), None)

  private def walk(
      start: Path,
      maxDepth: Int,
      currentDepth: Int,
      options: Array[FileVisitOption],
      visited: SSet[Path]
  ): SStream[Path] = {
    start #:: {
      if (!isDirectory(start, linkOptsFromFileVisitOpts(options))) SStream.empty
      else {
        FileHelpers
          .list(start.toString, (n, t) => (n, t))
          .toScalaStream
          .flatMap {
            case (name, FileHelpers.FileType.Link)
                if options.contains(FileVisitOption.FOLLOW_LINKS) =>
              val path = start.resolve(name)
              val newVisited = visited + path
              val target = readSymbolicLink(path)
              if (newVisited.contains(target))
                throw new UncheckedIOException(
                  new FileSystemLoopException(path.toString)
                )
              else if (!exists(target, Array(LinkOption.NOFOLLOW_LINKS)))
                start.resolve(name) #:: SStream.empty
              else
                walk(path, maxDepth, currentDepth + 1, options, newVisited)

            case (name, FileHelpers.FileType.Directory)
                if currentDepth < maxDepth =>
              val path = start.resolve(name)
              val newVisited =
                if (options.contains(FileVisitOption.FOLLOW_LINKS))
                  visited + path
                else visited
              walk(path, maxDepth, currentDepth + 1, options, newVisited)

            case (name, _) =>
              start.resolve(name) #:: SStream.empty
          }
      }
    }

  }

  def walkFileTree(start: Path, visitor: FileVisitor[_ >: Path]): Path =
    walkFileTree(
      start,
      EnumSet.noneOf(classOf[FileVisitOption]),
      Int.MaxValue,
      visitor
    )

  private case object TerminateTraversalException extends Exception

  def walkFileTree(
      start: Path,
      options: Set[FileVisitOption],
      maxDepth: Int,
      visitor: FileVisitor[_ >: Path]
  ): Path =
    try _walkFileTree(start, options, maxDepth, visitor)
    catch { case TerminateTraversalException => start }

  // The sense of how LinkOption follows links or not is somewhat
  // inverted because of a double negative.  The absense of
  // LinkOption.NOFOLLOW_LINKS means follow links, the default.
  // There is no explicit LinkOption.FOLLOW_LINKS.
  private def linkOptsFromFileVisitOpts(
      options: Array[FileVisitOption]
  ): Array[LinkOption] = {
    if (options.contains(FileVisitOption.FOLLOW_LINKS)) Array.empty[LinkOption]
    else Array(LinkOption.NOFOLLOW_LINKS)
  }

  private def _walkFileTree(
      start: Path,
      options: Set[FileVisitOption],
      maxDepth: Int,
      visitor: FileVisitor[_ >: Path]
  ): Path = {
    val nofollow = Array(LinkOption.NOFOLLOW_LINKS)
    val optsArray = options.toArray(new Array[FileVisitOption](options.size()))
    val stream = walk(start, maxDepth, 0, optsArray, SSet.empty)
    val dirsToSkip = scala.collection.mutable.Set.empty[Path]
    val openDirs = scala.collection.mutable.Stack.empty[Path]
    stream.foreach { p =>
      val parent = p.getParent()

      if (dirsToSkip.contains(parent)) ()
      else {
        try {
          val brokenSymLink =
            if (isSymbolicLink(p)) {
              val target = readSymbolicLink(p)
              val targetExists = exists(target, nofollow)
              !targetExists
            } else false

          val linkOpts =
            if (!brokenSymLink) linkOptsFromFileVisitOpts(optsArray)
            else nofollow

          val attributes =
            getFileAttributeView(p, classOf[BasicFileAttributeView], linkOpts)
              .readAttributes()

          while (openDirs.nonEmpty && !parent.startsWith(openDirs.head)) {
            visitor.postVisitDirectory(openDirs.pop(), null)
          }

          val result =
            if (attributes.isRegularFile()) {
              visitor.visitFile(p, attributes)
            } else if (attributes.isDirectory()) {
              openDirs.push(p)
              visitor.preVisitDirectory(p, attributes) match {
                case FileVisitResult.SKIP_SUBTREE =>
                  openDirs.pop(); FileVisitResult.SKIP_SUBTREE
                case other => other
              }
            } else if (attributes.isSymbolicLink()) {
              visitor.visitFile(p, attributes)
            } else {
              FileVisitResult.CONTINUE
            }

          result match {
            case FileVisitResult.TERMINATE =>
              throw TerminateTraversalException
            case FileVisitResult.SKIP_SUBTREE  => dirsToSkip += p
            case FileVisitResult.SKIP_SIBLINGS => dirsToSkip += parent
            case FileVisitResult.CONTINUE      => ()
          }

        } catch {
          // Give the visitor a last chance to fix things up.
          case e: IOException => visitor.visitFileFailed(p, e)
        }
      }
    }

    while (openDirs.nonEmpty) {
      visitor.postVisitDirectory(openDirs.pop(), null)
    }
    start
  }

  def write(
      path: Path,
      bytes: Array[Byte],
      _options: Array[OpenOption]
  ): Path = {
    val options =
      if (_options.isEmpty)
        Array[OpenOption](
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
      else _options

    val out = newOutputStream(path, options)
    out.write(bytes)
    out.close()
    path
  }

  def write(
      path: Path,
      lines: Iterable[_ <: CharSequence],
      cs: Charset,
      _options: Array[OpenOption]
  ): Path = {
    val options =
      if (_options.isEmpty)
        Array[OpenOption](
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
      else _options
    val writer = newBufferedWriter(path, cs, options)
    val it = lines.iterator()
    while (it.hasNext()) {
      writer.append(it.next())
      writer.newLine()
    }
    writer.close()
    path
  }

  def write(
      path: Path,
      lines: Iterable[_ <: CharSequence],
      options: Array[OpenOption]
  ): Path =
    write(path, lines, StandardCharsets.UTF_8, options)

  private def setAttributes(path: Path, attrs: Array[FileAttribute[_]]): Unit =
    attrs.map(a => (a.name(), a.value())).toMap.foreach {
      case (name, value) =>
        setAttribute(path, name, value.asInstanceOf[AnyRef], Array.empty)
    }

  private val attributesClassesToViews: SMap[Class[
    _ <: BasicFileAttributes
  ], Class[_ <: BasicFileAttributeView]] =
    SMap(
      classOf[BasicFileAttributes] -> classOf[BasicFileAttributeView],
      classOf[DosFileAttributes] -> classOf[DosFileAttributeView],
      classOf[PosixFileAttributes] -> classOf[PosixFileAttributeView]
    )

  private val viewNamesToClasses: SMap[String, Class[_ <: FileAttributeView]] =
    SMap(
      "acl" -> classOf[AclFileAttributeView],
      "basic" -> classOf[BasicFileAttributeView],
      "dos" -> classOf[DosFileAttributeView],
      "owner" -> classOf[FileOwnerAttributeView],
      "user" -> classOf[UserDefinedFileAttributeView],
      "posix" -> classOf[PosixFileAttributeView]
    )

}
