package ua.pico.jedit.markdown;

import org.gjt.sp.jedit.Buffer;
import org.gjt.sp.jedit.EditPlugin;
import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.Registers;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.io.VFS;
import org.gjt.sp.jedit.textarea.Selection;
import org.gjt.sp.util.Log;
import org.gjt.sp.util.ThreadUtilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import infoviewer.InfoViewerPlugin;

public class MarkdownPlugin extends EditPlugin {

	public static final String NAME = "markdown";
	public static final String OPTION_PREFIX = "options.markdown.";

	private static final String MERMAID_JS = "mermaid.min.js";
	private static final String SVG_PAN_ZOOM_JS = "svg-pan-zoom.min.js";
	private static final String HIGHLIGHT_JS = "highlight.min.js";
	private static final String HIGHLIGHT_CSS = "highlight-github.min.css";

	/**
	 * Initializes mermaid and, for every rendered diagram, displays it
	 * at natural scale. The frame's width and height always match the
	 * currently displayed content's size: the width is floored at the
	 * container's own width (so it never looks like a narrow column,
	 * and grows a horizontal scrollbar once zoomed, or for a naturally
	 * wide diagram from the start, past that); the height simply
	 * follows the zoom level (so zooming out shrinks the frame right
	 * along with the content instead of leaving blank space below it),
	 * floored at 70px to keep a single-row diagram from becoming an
	 * unreadable sliver when zoomed far out. Alt(Option)+drag pans the
	 * diagram and Alt(Option)+wheel zooms it (rarely needed now that
	 * the frame always fits the content, but kept for edge cases);
	 * plain drag and double-click are left to the browser's native
	 * text selection (plain wheel scrolls the page as usual). On
	 * window resize, the width floor is simply re-measured
	 * off the parent element (since the container itself may currently
	 * be wider than that due to zooming) and the frame re-applied at
	 * the current zoom level.
	 */
	private static final String MERMAID_INIT_SCRIPT =
		"mermaid.initialize({startOnLoad: false, flowchart: {useMaxWidth: false}, sequence: {useMaxWidth: false}, gantt: {useMaxWidth: false}});" +
		"mermaid.run({querySelector: '.mermaid', postRenderCallback: function(id) {" +
		"  var svg = document.getElementById(id);" +
		"  if (!svg) { return; }" +
		"  var box = svg.parentNode;" +
		"  box.style.maxWidth = 'none';" +
		"  var naturalW = svg.viewBox.baseVal.width;" +
		"  var naturalH = svg.viewBox.baseVal.height;" +
		"  var minW = box.parentNode.getBoundingClientRect().width;" +
		"  function applyFrame(z) {" +
		"    var w = Math.max(minW, naturalW * z);" +
		"    var h = Math.max(70, naturalH * z);" +
		"    svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);" +
		"    svg.setAttribute('width', w);" +
		"    svg.setAttribute('height', h);" +
		"    box.style.width = w + 'px';" +
		"    box.style.height = h + 'px';" +
		"    return h;" +
		"  }" +
		"  applyFrame(1);" +
		"  svg.style.width = '100%';" +
		"  svg.style.height = '100%';" +
		"  var pz = svgPanZoom(svg, {panEnabled: false, zoomEnabled: true, controlIconsEnabled: false, fit: false, center: false, minZoom: 0.1, maxZoom: 20, mouseWheelZoomEnabled: false, dblClickZoomEnabled: false, preventMouseEventsDefault: false});" +
		"  pz.pan({x: 0, y: 0});" +
		"  svg.addEventListener('wheel', function(evt) {" +
		"    if (!evt.altKey) { return; }" +
		"    evt.preventDefault();" +
		"    var rect = svg.getBoundingClientRect();" +
		"    var point = {x: evt.clientX - rect.left, y: evt.clientY - rect.top};" +
		"    var factor = evt.deltaY < 0 ? 1.1 : 0.9;" +
		"    pz.zoomAtPointBy(factor, point);" +
		"    applyFrame(pz.getZoom());" +
		"  });" +
		"  var isPanning = false;" +
		"  var lastPoint = null;" +
		"  svg.addEventListener('mousedown', function(evt) {" +
		"    if (!evt.altKey) { return; }" +
		"    isPanning = true;" +
		"    lastPoint = {x: evt.clientX, y: evt.clientY};" +
		"    evt.preventDefault();" +
		"  });" +
		"  window.addEventListener('mousemove', function(evt) {" +
		"    if (!isPanning) { return; }" +
		"    var dx = evt.clientX - lastPoint.x;" +
		"    var dy = evt.clientY - lastPoint.y;" +
		"    lastPoint = {x: evt.clientX, y: evt.clientY};" +
		"    pz.panBy({x: dx, y: dy});" +
		"  });" +
		"  window.addEventListener('mouseup', function() { isPanning = false; });" +
		"  var resizeTimer = null;" +
		"  window.addEventListener('resize', function() {" +
		"    if (resizeTimer) { clearTimeout(resizeTimer); }" +
		"    resizeTimer = setTimeout(function() {" +
		"      minW = box.parentNode.getBoundingClientRect().width;" +
		"      applyFrame(pz.getZoom());" +
		"    }, 150);" +
		"  });" +
		"}});";

	public MarkdownPlugin() {
		super();
	}

	@Override
	public void start() {
		extractResource(MERMAID_JS);
		extractResource(SVG_PAN_ZOOM_JS);
		extractResource(HIGHLIGHT_JS);
		extractResource(HIGHLIGHT_CSS);
	}

	/**
	 * Extracts a resource bundled in the plugin jar to the plugin home,
	 * so the preview HTML can reference it as a local file (no network
	 * access needed).
	 */
	private void extractResource(final String name) {
		final File home = getPluginHome();

		if (null == home) {
			return;
		}

		final File target = new File(home, name);

		if (target.exists()) {
			return;
		}
		if (!home.exists() && !home.mkdirs()) {
			Log.log(Log.ERROR, MarkdownPlugin.class, "Cannot create plugin home: " + home);
			return;
		}
		try (InputStream in = MarkdownPlugin.class.getResourceAsStream("/" + name)) {
			if (null == in) {
				Log.log(Log.ERROR, MarkdownPlugin.class, name + " not found in the plugin jar.");
				return;
			}
			Files.copy(in, target.toPath());
			Log.log(Log.DEBUG, MarkdownPlugin.class, "Extracted " + name + " to " + target);
		} catch (IOException ioex) {
			Log.log(Log.ERROR, MarkdownPlugin.class, "Cannot extract " + name + ": " + ioex.getMessage());
		}
	}

	public void renderBuffer(final View view, final Buffer markdownBuffer) {
		renderBuffer(view, markdownBuffer, null);
	}

	public void renderSelection(final View view, final Buffer markdownBuffer, final Selection[] selections) {
		renderSelection(view, markdownBuffer, selections, null);
	}

	public void previewBuffer(final View view, final Buffer markdownBuffer) {
		renderBuffer(view, markdownBuffer, Target.Browser);
	}

	public void previewSelection(final View view, final Buffer markdownBuffer, final Selection[] selections) {
		renderSelection(view, markdownBuffer, selections, Target.Browser);
	}

	public enum Target {
		Buffer,
		Clipboard,
		Browser;
	}
	
	private static final String MODE = "html";

	private void renderBuffer(final View view, final Buffer markdownBuffer, Target target) {
		final MarkdownUtil util = MarkdownUtil.getInstance();
		String text = markdownBuffer.getText(0, markdownBuffer.getLength());

		if (0 == text.length()) {
			view.getToolkit().beep();
			Log.log(Log.WARNING, MarkdownPlugin.class, "Buffer is empty.");
			JOptionPane.showMessageDialog(null, "Buffer is empty.", "Markdown Plugin", JOptionPane.WARNING_MESSAGE);

			return;
		}

		if (null == target) {
			target = util.getTarget();
		}
		text = util.render(text);
		switch (target) {
		case Clipboard:
			saveToClipboard(text);
			break;
		case Browser:
			showPreview(view, markdownBuffer, text);
			break;
		case Buffer:
		default:
			saveToBuffer(view, text);
		}
	}

	private void renderSelection(final View view, final Buffer markdownBuffer, final Selection[] selections, Target target) {
		final String newLine = "\n";
		final MarkdownUtil util = MarkdownUtil.getInstance();
		final StringBuilder selected = new StringBuilder();
		
		String text;

		if (0 == selections.length) {
			view.getToolkit().beep();
			Log.log(Log.WARNING, MarkdownPlugin.class, "Selection is empty.");
			JOptionPane.showMessageDialog(null, "No selected text.", "Markdown Plugin", JOptionPane.WARNING_MESSAGE);

			return;
		}

		if (null == target) {
			target = util.getTarget();
		}
		for (Selection selection : selections) {
			text = markdownBuffer.getText(selection.getStart(), selection.getEnd() - selection.getStart());
			selected.append(text);
			if (!text.endsWith(newLine)) {
				selected.append(newLine);
			}
		}
		text = util.render(selected.toString());
		switch (target) {
		case Clipboard:
			saveToClipboard(text);
			break;
		case Browser:
			showPreview(view, markdownBuffer, text);
			break;
		case Buffer:
		default:
			saveToBuffer(view, text);
		}
	}

	private void saveToBuffer(final View view, final String text) {
		final Buffer htmlBuffer = jEdit.newFile(view);

		Log.log(Log.DEBUG, MarkdownPlugin.class, "Render to a new buffer.");
		htmlBuffer.insert(0, text);
		htmlBuffer.setMode(MODE);
		view.setBuffer(htmlBuffer);
	}

	private void saveToClipboard(final String text) {
		final Registers.ClipboardRegister clipboard = (Registers.ClipboardRegister) Registers.getRegister('$');

		Log.log(Log.DEBUG, MarkdownPlugin.class, "Render to clipboard.");
		clipboard.setValue(text);
	}

	private static final Pattern IMG_SRC_PATTERN = Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern A_HREF_PATTERN = Pattern.compile("<a\\b[^>]*?\\bhref\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern URI_SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

	/**
	 * Finds every distinct <img src="..."> / <a href="..."> reference in
	 * the rendered HTML that looks like a relative path rather than an
	 * absolute URL (http://, mailto:, data:, etc., which the browser
	 * can already resolve on its own) or a same-page anchor (#section).
	 */
	private static List<String> findRelativeResources(final String html) {
		final Set<String> found = new LinkedHashSet<>();

		collectRelativeReferences(html, IMG_SRC_PATTERN, found);
		collectRelativeReferences(html, A_HREF_PATTERN, found);

		return new ArrayList<>(found);
	}

	private static void collectRelativeReferences(final String html, final Pattern pattern, final Set<String> found) {
		final Matcher matcher = pattern.matcher(html);

		while (matcher.find()) {
			final String ref = matcher.group(1);

			if (!ref.isEmpty() && '#' != ref.charAt(0) && !URI_SCHEME_PATTERN.matcher(ref).find()) {
				found.add(ref);
			}
		}
	}

	/**
	 * Strips a trailing #fragment or ?query from a relative reference,
	 * returning just the file path part to resolve and copy. The
	 * reference itself is left untouched in the HTML, so the browser
	 * still applies the fragment/query once the underlying file is
	 * reachable at that same relative path.
	 */
	private static String stripFragmentOrQuery(final String ref) {
		final int hash = ref.indexOf('#');
		final int query = ref.indexOf('?');
		final int cut = -1 == hash ? query : (-1 == query ? hash : Math.min(hash, query));

		return -1 == cut ? ref : ref.substring(0, cut);
	}

	/**
	 * Copies each relative resource reference (image or link target)
	 * from the buffer's own VFS directory into targetDir, preserving
	 * its relative path so the reference in the already-rendered HTML
	 * keeps resolving without needing to be rewritten. The preview HTML
	 * always lives in the OS temp directory rather than next to the
	 * source file, so this runs for every buffer (local or opened
	 * through a VFS like sftp://) that references any relative-path
	 * resource. A link to another Markdown file is copied as-is (not
	 * rendered), so it opens as plain text rather than a preview.
	 * Failing to copy one resource is logged and skipped rather than
	 * aborting the whole preview.
	 */
	private void copyRelativeResources(final View view, final Buffer buffer, final List<String> resources, final File targetDir) {
		final VFS vfs = buffer.getVFS();

		for (final String ref : resources) {
			final String relPath = stripFragmentOrQuery(ref);

			if (relPath.isEmpty()) {
				continue;
			}
			try {
				final File targetFile = new File(targetDir, relPath);

				// relPath may contain "../" segments (e.g. a link to a
				// file above the buffer's own directory); reject any
				// that, once resolved, would land outside targetDir
				// rather than following it wherever it points on the
				// local filesystem.
				if (!isWithinDirectory(targetDir, targetFile)) {
					Log.log(Log.WARNING, MarkdownPlugin.class, "Refusing to copy resource outside the preview directory: " + relPath);
					continue;
				}

				final String sourcePath = vfs.constructPath(buffer.getDirectory(), relPath);
				final File parent = targetFile.getParentFile();

				if (null != parent && !parent.exists() && !parent.mkdirs()) {
					Log.log(Log.WARNING, MarkdownPlugin.class, "Cannot create directory: " + parent);
					continue;
				}
				if (VFS.copy(null, sourcePath, targetFile.getPath(), view, false)) {
					targetFile.deleteOnExit();
				} else {
					Log.log(Log.WARNING, MarkdownPlugin.class, "Cannot copy resource: " + sourcePath);
				}
			} catch (Exception ex) {
				Log.log(Log.WARNING, MarkdownPlugin.class, "Cannot copy resource \"" + relPath + "\": " + ex.getMessage());
			}
		}
	}

	/**
	 * Checks, via canonical paths (so "../" segments are resolved),
	 * that file is dir itself or a descendant of it.
	 */
	private static boolean isWithinDirectory(final File dir, final File file) throws IOException {
		final String dirPath = dir.getCanonicalPath();
		final String filePath = file.getCanonicalPath();

		return filePath.equals(dirPath) || filePath.startsWith(dirPath + File.separator);
	}

	private void showPreview(final View view, final Buffer buffer, final String text) {
		final InfoViewerPlugin browser = (InfoViewerPlugin) jEdit.getPlugin("infoviewer.InfoViewerPlugin");

		if (null == browser) {
			final String message = "InfoViewer plugin not found.";

			view.getToolkit().beep();
			Log.log(Log.ERROR, MarkdownPlugin.class, message);
			JOptionPane.showMessageDialog(null, message, "Markdown Plugin", JOptionPane.ERROR_MESSAGE);

			return;
		}

		final String name = buffer.isUntitled() ? "Markdown text" : buffer.getName();
		final List<String> resources = findRelativeResources(text);

		if (resources.isEmpty()) {
			renderAndOpenPreview(view, buffer, browser, text, name, resources);
		} else {
			// Copying goes over the VFS (network I/O for sftp:// etc.,
			// but also plain local file I/O) and must not block the UI
			// thread.
			ThreadUtilities.runInBackground(new Runnable() {
				public void run() {
					renderAndOpenPreview(view, buffer, browser, text, name, resources);
				}
			});
		}
	}

	private void renderAndOpenPreview(final View view, final Buffer buffer, final InfoViewerPlugin browser,
			final String text, final String name, final List<String> resources) {
		final String html_epilogue = "</body></html>";
		final String charset = buffer.getStringProperty(buffer.ENCODING);
		final String css = jEdit.getProperty(OPTION_PREFIX + "preview.css", "");
		final File pluginHome = getPluginHome();
		final File mermaidJs = null == pluginHome ? null : new File(pluginHome, MERMAID_JS);
		final File svgPanZoomJs = null == pluginHome ? null : new File(pluginHome, SVG_PAN_ZOOM_JS);
		final File highlightJs = null == pluginHome ? null : new File(pluginHome, HIGHLIGHT_JS);
		final File highlightCss = null == pluginHome ? null : new File(pluginHome, HIGHLIGHT_CSS);
		File html = null;

		try {
			// A dedicated, uniquely-named directory per preview (rather
			// than writing straight into the shared OS temp directory
			// root): a VFS URL (sftp:// etc.) can't be used as a local
			// path anyway, and keeping generated preview files out of
			// the buffer's own (often version-controlled) directory is
			// preferable even for local buffers. The dedicated directory
			// also avoids two previews' copied resources colliding when
			// their Markdown happens to share a relative subpath (e.g.
			// both referencing "images/foo.png"). Any relative-path
			// images/links the rendered HTML references are copied
			// alongside it below so they still resolve.
			final File previewDir = Files.createTempDirectory("MarkdownPlugin-preview-").toFile();

			previewDir.deleteOnExit();
			html = File.createTempFile(name, "." + MODE, previewDir);
			if (!resources.isEmpty()) {
				copyRelativeResources(view, buffer, resources, html.getParentFile());
			}

			final Writer writer = new OutputStreamWriter(new FileOutputStream(html), charset);
			final StringBuilder builder = new StringBuilder();

			builder.append("<!DOCTYPE html><html><head><meta charset=\"").append(charset).append("\"/><title>").append(name).append("</title>");
			if (null != highlightCss && highlightCss.exists()) {
				// Loaded before the user's own preview.css (below) so that
				// CSS's normal "later rule wins" cascade lets preview.css
				// override the theme's defaults (e.g. code block background).
				builder.append("<link rel=\"stylesheet\" href=\"").append(highlightCss.toURI().toURL().toString()).append("\"/>");
			}
			if (0 != css.length()) {
				builder.append("<style>").append(css).append("</style>");
			}
			builder.append("</head><body>");
			builder.append(text);
			if (null != mermaidJs && mermaidJs.exists() && null != svgPanZoomJs && svgPanZoomJs.exists()) {
				// Placed after the diagram markup (not in <head>) so the
				// .mermaid elements already exist in the DOM by the time
				// mermaid.run() scans for them.
				builder.append("<script src=\"").append(mermaidJs.toURI().toURL().toString()).append("\"></script>");
				builder.append("<script src=\"").append(svgPanZoomJs.toURI().toURL().toString()).append("\"></script>");
				builder.append("<script>").append(MERMAID_INIT_SCRIPT).append("</script>");
			}
			if (null != highlightJs && highlightJs.exists()) {
				// Placed after the code block markup (not in <head>) so
				// the <pre><code> elements already exist in the DOM by
				// the time hljs.highlightAll() scans for them.
				builder.append("<script src=\"").append(highlightJs.toURI().toURL().toString()).append("\"></script>");
				builder.append("<script>hljs.highlightAll();</script>");
			}
			builder.append(html_epilogue);
			writer.write(builder.toString());
			writer.close();
			Log.log(Log.DEBUG, MarkdownPlugin.class, "Preview in browser.");

			final File finalHtml = html;

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					try {
						browser.openURL(view, finalHtml.toURI().toURL().toString());
					} catch (IOException ioex) {
						Log.log(Log.ERROR, MarkdownPlugin.class, "Cannot open preview: " + ioex.getMessage());
					}
				}
			});
		} catch (IOException ioex) {
			final String message = "Cannot create a temporary file: " + ioex.getMessage();

			Log.log(Log.ERROR, MarkdownPlugin.class, message);
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					view.getToolkit().beep();
					JOptionPane.showMessageDialog(null, message, "Markdown Plugin", JOptionPane.ERROR_MESSAGE);
				}
			});
		} finally {
			if (null != html) {
				html.deleteOnExit();
			}
		}
	}

}

