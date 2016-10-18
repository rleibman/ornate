package com.novocode.ornate

import java.io.{FileNotFoundException, InputStreamReader, BufferedReader}
import java.net.URI
import java.util.regex.Pattern

import com.novocode.ornate.commonmark.Attributed
import com.novocode.ornate.commonmark.AttributedFencedCodeBlock
import com.novocode.ornate.commonmark.NodeExtensionMethods
import NodeExtensionMethods._
import com.novocode.ornate.commonmark.PageProcessor
import com.novocode.ornate.config.{ConfiguredObject, Global}
import com.novocode.ornate.config.ConfigExtensionMethods.configExtensionMethods
import com.typesafe.config.ConfigValueType
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock

import scala.collection.mutable.ArrayBuffer
import scala.io.Codec

/** Include code snippets from external files in fenced code blocks. */
class IncludeCodeExtension(co: ConfiguredObject) extends Extension {
  override def pageProcessors(site: Site) = Seq(new IncludeCodeProcessor(co))
}

class IncludeCodeProcessor(co: ConfiguredObject) extends PageProcessor with Logging {
  class IncludeCodeVisitor(p: Page) extends AbstractVisitor {
    lazy val removePatterns = co.getConfig(p.config).getConfigMapOr("remove").iterator.collect {
      case (k, v) if v.valueType != ConfigValueType.NULL => (k, v.unwrapped.toString)
    }.toMap

    override def visit(n: FencedCodeBlock): Unit = {
      val attr = n match {
        case n: AttributedFencedCodeBlock => n
        case n => Attributed.parse(n.getInfo)
      }
      attr.defAttrs.get("src").foreach { src =>
        try {
          p.sourceFileURI match {
            case Some(baseURI) =>
              logger.debug(s"Including snippet $src on page ${p.uri}")
              val snippetURI = baseURI.resolve(src)
              getSnippet(snippetURI) match {
                case Some(s) => n.setLiteral(s)
                case None =>
                  logger.error(s"No content found for snippet $src on page ${p.uri}")
              }
            case None =>
              logger.error(s"Cannot include snippet $src on synthetic page ${p.uri}")
          }
        } catch {
          case ex: FileNotFoundException => logger.error(s"Page ${p.uri}: File for snippet $src not found")
          case ex: Exception => logger.error(s"Page ${p.uri}: Error including snippet $src", ex)
        }
      }
    }

    def getSnippet(snippetURI: URI): Option[String] = {
      val snippetPath = snippetURI.getPath
      val remove: Option[Pattern] = removePatterns.find { case (ext, re) =>
        snippetPath.endsWith(s".$ext")
      }.map { case (ext, re) => Pattern.compile(re) }
      val fileURI = new URI(snippetURI.getScheme, snippetURI.getUserInfo, snippetURI.getHost,
        snippetURI.getPort, snippetPath, snippetURI.getQuery, null)
      val fragment = snippetURI.getFragment
      val in = fileURI.toURL.openStream()
      val lines = try {
        val bin = new BufferedReader(new InputStreamReader(in, Codec.UTF8.decoder))
        Iterator.continually(bin.readLine()).takeWhile(_ ne null).toVector
      } finally in.close
      val trimmed = lines.map { s =>
        val last = s.lastIndexWhere(c => c != ' ' && c != '\t')
        if(last == -1) "" else s.substring(0, last+1)
      }
      if(fragment eq null) {
        val first = trimmed.indexWhere(_.nonEmpty)
        val last = trimmed.lastIndexWhere(_.nonEmpty)
        val content = trimmed.slice(first, last+1)
        Some(content.mkString("\n"))
      } else {
        val suffix = "#" + fragment
        logger.debug("Building snippet for fragement "+suffix)
        var found = false
        val buf = new ArrayBuffer[String]
        var blockBuf: ArrayBuffer[String] = null
        var inBlock = false
        var startOffset = 0
        def startBlock(offset: Int): Unit = {
          found = true
          inBlock = true
          blockBuf = new ArrayBuffer[String]
          startOffset = offset
        }
        def endBlock(endOffset: Int): Unit = {
          inBlock = false
          var offset = math.min(startOffset, endOffset)
          blockBuf.foreach { s =>
            val soff = s.indexWhere(c => c != ' ' && c != '\t')
            if(soff >= 0) offset = math.min(offset, soff)
          }
          blockBuf.foreach(s => buf += s.substring(math.min(offset, s.length)))
        }
        trimmed.foreach { s =>
          if(s.endsWith(suffix)) {
            val offset = s.length - suffix.length
            if(inBlock) endBlock(offset)
            else startBlock(offset)
          } else if(inBlock && !remove.exists(p => p.matcher(s).matches())) blockBuf += s
        }
        if(inBlock) endBlock(0)
        if(found) Some(buf.mkString("\n")) else None
      }
    }
  }

  def apply(p: Page): Unit = p.doc.accept(new IncludeCodeVisitor(p))
}
