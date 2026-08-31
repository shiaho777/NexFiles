/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webdavserver

import fi.iki.elonen.NanoHTTPD
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.size
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A minimal but usable WebDAV server on top of NanoHTTPD, exposing a single local directory tree
 * over HTTP so that a computer can mount it (macOS Finder, Windows Explorer, Linux davfs2).
 *
 * Scope is deliberately restricted to the local filesystem, mirroring the recycle bin's choice:
 * the value of a built-in WebDAV server is "browse this phone's storage from my computer", and
 * cross-provider remounting would add far more complexity than it's worth.
 *
 * Implements the subset of WebDAV methods that real-world clients rely on: OPTIONS, PROPFIND,
 * GET, PUT, DELETE, MKCOL, MOVE, COPY. Locking (LOCK/UNLOCK) is intentionally omitted — Linux/macOS
 * mounts don't need it, and Windows would need a fake-token implementation that's not worth the
 * surface area.
 *
 * @param port TCP port to listen on; NanoHTTPD recommends >1024.
 * @param rootDirectory The local directory served as the WebDAV root.
 * @param username Optional basic-auth username; when null/empty, no auth is required.
 * @param password Optional basic-auth password.
 */
class WebDavServer(
    port: Int,
    private val rootDirectory: Path,
    private val username: String?,
    private val password: String?
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        // Basic auth gate, but only if credentials were configured.
        if (!username.isNullOrEmpty()) {
            val auth = session.headers["authorization"]
            if (!isAuthorized(auth)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "").apply {
                    addHeader("WWW-Authenticate", "Basic realm=\"NexFiles WebDAV\"")
                }
            }
        }
        return try {
            when (session.method) {
                Method.OPTIONS -> handleOptions()
                Method.PROPFIND -> handlePropfind(session)
                Method.GET -> handleGet(session)
                Method.HEAD -> handleGet(session)
                Method.PUT -> handlePut(session)
                Method.DELETE -> handleDelete(session)
                Method.MKCOL -> handleMkcol(session)
                Method.MOVE -> handleMoveCopy(session, copy = false)
                Method.COPY -> handleMoveCopy(session, copy = true)
                else -> newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed"
                )
            }
        } catch (e: SecurityException) {
            newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", e.message ?: "")
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Internal error"
            )
        }
    }

    private fun isAuthorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val token = header.removePrefix("Basic ").removePrefix("basic ").trim()
        val decoded = try {
            String(android.util.Base64.decode(token, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            return false
        }
        val (user, pass) = decoded.split(":", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }
        return user == username && pass == password
    }

    private fun handleOptions(): Response {
        // DAV class 1 support; advertise the methods clients probe for.
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
            addHeader("DAV", "1")
            addHeader("Allow", "OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY")
            addHeader("MS-Author-Via", "DAV")
        }
    }

    private fun handlePropfind(session: IHTTPSession): Response {
        val path = resolvePath(session)
        val depth = session.headers["depth"] ?: "infinity"
        // Read the request body so NanoHTTPD doesn't hang waiting for it; we ignore the actual
        // prop request and always return the standard set (size, dates, resourcetype).
        parseBody(session)
        if (!Files.exists(path)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<D:multistatus xmlns:D=\"DAV:\">")
            appendEntry(path, path)
            if (Files.isDirectory(path) && depth != "0") {
                Files.newDirectoryStream(path).use { stream ->
                    for (child in stream) {
                        appendEntry(child, path)
                    }
                }
            }
            append("</D:multistatus>")
        }
        return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml", xml).apply {
            addHeader("DAV", "1")
        }
    }

    private fun StringBuilder.appendEntry(file: Path, collectionRoot: Path) {
        val attrs = try {
            file.readAttributes(BasicFileAttributes::class.java)
        } catch (e: IOException) {
            null
        }
        append("<D:response>")
        append("<D:href>").append(escapeHref(hrefFor(file, collectionRoot))).append("</D:href>")
        append("<D:propstat><D:prop>")
        if (attrs != null && attrs.isDirectory) {
            append("<D:resourcetype><D:collection/></D:resourcetype>")
        } else {
            append("<D:resourcetype/>")
        }
        if (attrs != null && !attrs.isDirectory) {
            append("<D:getcontentlength>").append(attrs.size()).append("</D:getcontentlength>")
        }
        val mtime = attrs?.lastModifiedTime()?.toMillis() ?: 0L
        append("<D:getlastmodified>").append(toHttpDate(mtime)).append("</D:getlastmodified>")
        append("</D:prop>")
        append("<D:status>HTTP/1.1 200 OK</D:status>")
        append("</D:propstat>")
        append("</D:response>")
    }

    private fun handleGet(session: IHTTPSession): Response {
        val path = resolvePath(session)
        if (!Files.exists(path)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        if (Files.isDirectory(path)) {
            // Some clients GET a directory; return a simple HTML index rather than a 405.
            return newFixedLengthResponse(Response.Status.OK, "text/html", directoryIndex(path))
        }
        val mimeType = guessMime(path)
        val length = try { path.size() } catch (e: Exception) { -1L }
        val response = newChunkedResponse(Response.Status.OK, mimeType, path.newInputStream())
        if (length >= 0) {
            try {
                response.addHeader("Content-Length", length.toString())
            } catch (ignored: Exception) {}
        }
        return response
    }

    private fun handlePut(session: IHTTPSession): Response {
        val path = resolvePath(session)
        val parent = path.parent
        if (parent == null || !Files.isDirectory(parent)) {
            return newFixedLengthResponse(
                Response.Status.CONFLICT, "text/plain", "Parent does not exist"
            )
        }
        val files = HashMap<String, String>()
        try {
            // HTTPSession.parseBody lives on the session object, not on the server.
            session.parseBody(files)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Put failed"
            )
        }
        val payload = files["postData"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "text/plain", "No body"
        )
        // NanoHTTPD stages large uploads in a temp file; for the common case the in-memory string
        // is fine. This keeps the implementation simple at the cost of buffering the whole file.
        try {
            path.newOutputStream().use { it.write(payload.toByteArray()) }
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Write failed"
            )
        }
        return newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Created")
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val path = resolvePath(session)
        if (!Files.exists(path)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        return try {
            path.delete(Files.isDirectory(path))
            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Delete failed"
            )
        }
    }

    private fun handleMkcol(session: IHTTPSession): Response {
        val path = resolvePath(session)
        parseBody(session)
        return try {
            path.createDirectory()
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Created")
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Mkcol failed"
            )
        }
    }

    private fun handleMoveCopy(session: IHTTPSession, copy: Boolean): Response {
        val source = resolvePath(session)
        val destHeader = session.headers["destination"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "text/plain", "No destination"
        )
        // Destination is an absolute URI; strip scheme/host to get the path component.
        val destPath = urlToPath(destHeader)
        if (destPath == null || !destPath.startsWith(rootDirectory)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/plain", "Destination out of root"
            )
        }
        parseBody(session)
        return try {
            if (copy) {
                source.copyTo(destPath, StandardCopyOption.REPLACE_EXISTING)
            } else {
                source.moveTo(destPath, StandardCopyOption.REPLACE_EXISTING)
            }
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Done")
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Move/copy failed"
            )
        }
    }

    // -- helpers --

    /** Maps a request URI onto a path inside [rootDirectory], refusing escapes. */
    private fun resolvePath(session: IHTTPSession): Path {
        val uri = URLDecoder.decode(session.uri, "UTF-8")
        val path = urlToPath(uri) ?: rootDirectory
        return path
    }

    private fun urlToPath(uri: String): Path? {
        // Accept both absolute (http://host/foo) and path-only (/foo) forms.
        var pathPart = uri
        if (pathPart.contains("://")) {
            val afterScheme = pathPart.substringAfter("://")
            pathPart = afterScheme.substringAfter("/", "/")
        }
        val resolved = rootDirectory.resolve(pathPart.trimStart('/')).normalize()
        if (!resolved.startsWith(rootDirectory)) {
            return rootDirectory
        }
        return resolved
    }

    private fun hrefFor(file: Path, collectionRoot: Path): String {
        val relative = collectionRoot.relativize(file).toString().replace(java.io.File.separatorChar, '/')
        val hrefBase = sessionRelativeRoot(collectionRoot)
        return if (relative.isEmpty()) hrefBase else "$hrefBase/$relative"
    }

    private fun sessionRelativeRoot(collectionRoot: Path): String {
        val rootRelative = rootDirectory.relativize(collectionRoot).toString()
            .replace(java.io.File.separatorChar, '/')
        return "/" + rootRelative.trimStart('/')
    }

    private fun directoryIndex(path: Path): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset=\"utf-8\"><title>")
            .append(escapeHtml(path.fileName?.toString() ?: "/")).append("</title></head><body><ul>")
        Files.newDirectoryStream(path).use { stream ->
            for (child in stream) {
                val name = child.fileName?.toString() ?: continue
                sb.append("<li><a href=\"").append(escapeHtml(name))
                if (Files.isDirectory(child)) sb.append('/')
                sb.append("\">").append(escapeHtml(name)).append("</a></li>")
            }
        }
        sb.append("</ul></body></html>")
        return sb.toString()
    }

    /** Consumes the request body so the connection is reusable; result discarded. */
    private fun parseBody(session: IHTTPSession) {
        val files = HashMap<String, String>()
        try {
            // HTTPSession.parseBody lives on the session object, not on the server.
            session.parseBody(files)
        } catch (ignored: Exception) {}
    }

    private fun guessMime(path: Path): String {
        val name = path.fileName?.toString() ?: return "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "html", "htm" -> "text/html"
            "txt", "md" -> "text/plain"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun escapeHref(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun toHttpDate(epochMillis: Long): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMillis))
    }
}
