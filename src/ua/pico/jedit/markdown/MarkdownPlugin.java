package ua.pico.jedit.markdown;

import org.gjt.sp.jedit.Buffer;
import org.gjt.sp.jedit.EditPlugin;
import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.Registers;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.textarea.Selection;
import org.gjt.sp.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import javax.swing.JOptionPane;

import infoviewer.InfoViewerPlugin;

public class MarkdownPlugin extends EditPlugin {

	public static final String NAME = "markdown";
	public static final String OPTION_PREFIX = "options.markdown.";

	private static final String MERMAID_JS = "mermaid.min.js";

	public MarkdownPlugin() {
		super();
	}

	@Override
	public void start() {
		extractMermaidJs();
	}

	/**
	 * Extracts the mermaid.min.js bundled in the plugin jar to the plugin
	 * home, so the preview HTML can reference it as a local file (no
	 * network access needed to render mermaid diagrams).
	 */
	private void extractMermaidJs() {
		final File home = getPluginHome();

		if (null == home) {
			return;
		}

		final File mermaidJs = new File(home, MERMAID_JS);

		if (mermaidJs.exists()) {
			return;
		}
		if (!home.exists() && !home.mkdirs()) {
			Log.log(Log.ERROR, MarkdownPlugin.class, "Cannot create plugin home: " + home);
			return;
		}
		try (InputStream in = MarkdownPlugin.class.getResourceAsStream("/" + MERMAID_JS)) {
			if (null == in) {
				Log.log(Log.ERROR, MarkdownPlugin.class, MERMAID_JS + " not found in the plugin jar.");
				return;
			}
			Files.copy(in, mermaidJs.toPath());
			Log.log(Log.DEBUG, MarkdownPlugin.class, "Extracted " + MERMAID_JS + " to " + mermaidJs);
		} catch (IOException ioex) {
			Log.log(Log.ERROR, MarkdownPlugin.class, "Cannot extract " + MERMAID_JS + ": " + ioex.getMessage());
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

	private void showPreview(final View view, final Buffer buffer, final String text) {
		final String html_epilogue = "</body></html>";
		final InfoViewerPlugin browser = (InfoViewerPlugin) jEdit.getPlugin("infoviewer.InfoViewerPlugin");
		final String charset = buffer.getStringProperty(buffer.ENCODING);
		final String css = jEdit.getProperty(OPTION_PREFIX + "preview.css", "");
		final File pluginHome = getPluginHome();
		final File mermaidJs = null == pluginHome ? null : new File(pluginHome, MERMAID_JS);
		String name;
		File html = null;
		Writer writer;
		StringBuilder builder = new StringBuilder();

		if (null == browser) {
			final String message = "InfoViewer plugin not found.";

			view.getToolkit().beep();
			Log.log(Log.ERROR, MarkdownPlugin.class, message);
			JOptionPane.showMessageDialog(null, message, "Markdown Plugin", JOptionPane.ERROR_MESSAGE);

			return;
		}

		if (buffer.isUntitled()) {
			name = "Markdown text";
		} else {
			name = buffer.getName();
		}
		try {
			html = File.createTempFile(name, "." + MODE, new File(buffer.getDirectory()));
			writer = new OutputStreamWriter(new FileOutputStream(html), charset);
			builder.append("<!DOCTYPE html><html><head><meta charset=\"").append(charset).append("\"/><title>").append(name).append("</title>");
			if (0 != css.length()) {
				builder.append("<style>").append(css).append("</style>");
			}
			if (null != mermaidJs && mermaidJs.exists()) {
				builder.append("<script src=\"").append(mermaidJs.toURI().toURL().toString()).append("\"></script>");
				builder.append("<script>mermaid.initialize({startOnLoad: true});</script>");
			}
			builder.append("</head><body>");
			builder.append(text).append(html_epilogue);
			writer.write(builder.toString());
			writer.close();
			Log.log(Log.DEBUG, MarkdownPlugin.class, "Preview in browser.");
			browser.openURL(view, html.toURI().toURL().toString());
		} catch (IOException ioex) {
			final String message = "Cannot create a temporary file: " + ioex.getMessage();

			view.getToolkit().beep();
			Log.log(Log.ERROR, MarkdownPlugin.class, message);
			JOptionPane.showMessageDialog(null, message, "Markdown Plugin", JOptionPane.ERROR_MESSAGE);

			return;
		} finally {
			if (null != html) {
				html.deleteOnExit();
			}
		}
		
	}

}

