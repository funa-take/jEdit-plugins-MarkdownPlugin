package ua.pico.jedit.markdown;

import org.gjt.sp.jedit.EditPlugin;
import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.util.Log;

import com.vladsch.flexmark.ext.abbreviation.AbbreviationExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.typographic.TypographicExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Markdown utility class
 * @author [Vitaliy Berdinskikh UR6LAD](mailto:ur6lad@i.ua)
 */
public class MarkdownUtil extends EditPlugin {

	static final String NONE_EXTENSIONS = "none";
	static final String ALL_EXTENSIONS = "all";
	static final String[] EXTENSION_NAME = new String[] {
		"abbreviations", "autolinks", "hardwraps", "quotes", "smarts",
		"smartypants", "tables", "noBlocks", "noInline", "noHypertext"
	};
	/**
	 * The subset of EXTENSION_NAME that "Enable all available extensions"
	 * actually enables. Deliberately excludes noBlocks/noInline/noHypertext,
	 * which suppress HTML output rather than add a feature - bundling them
	 * into "all" meant enabling every extension silently turned off
	 * &lt;details&gt; and other raw HTML in the preview. Choosing extensions
	 * individually can still enable them.
	 */
	static final String[] ALL_EXTENSION_NAME = new String[] {
		"abbreviations", "autolinks", "hardwraps", "quotes", "smarts",
		"smartypants", "tables"
	};
	static final String TARGET = "target";

	public static MarkdownUtil getInstance() {
		return singleton;
	}

	/**
	 * Returns the names of the currently enabled extensions.
	 * @see #EXTENSION_NAME
	 */
	public Set<String> getExtensions() {
		return extensionNames;
	}

	/**
	 * Sets the new set of enabled extensions (by name, see EXTENSION_NAME)
	 * and rebuilds the parser/renderer.
	 * @param extensionNames The names of the extensions to enable.
	 */
	public void setExtensions(final Set<String> extensionNames) {
		this.extensionNames = saveExtensions(extensionNames);
		build(this.extensionNames);
	}

	/**
	 * Return current target (buffer, clipboard or browser).
	 * @see MarkdownPlugin.Target
	 */
	public MarkdownPlugin.Target getTarget() {
		return target;
	}

	/**
	 * Set the new target.
	 * @see MarkdownPlugin.Target
	 */
	public void setTarget(final MarkdownPlugin.Target target) {
		jEdit.setProperty(MarkdownPlugin.OPTION_PREFIX + TARGET, target.name());
		this.target = target;
	}

	/**
	 * Convert the given Markdown text to HTML using the current extension settings.
	 * @param text Markdown source text.
	 * @return Rendered HTML.
	 */
	public String render(final String text) {
		final Node document = parser.parse(text);

		return renderer.render(document);
	}

	private static MarkdownUtil singleton;

	private Parser parser;
	private HtmlRenderer renderer;
	private Set<String> extensionNames;
	private MarkdownPlugin.Target target;

	static {
		singleton = new MarkdownUtil();
	}

	private MarkdownUtil() {
		extensionNames = readExtensions();
		build(extensionNames);
		try {
			target = Enum.valueOf(MarkdownPlugin.Target.class, jEdit.getProperty(MarkdownPlugin.OPTION_PREFIX + TARGET, MarkdownPlugin.Target.Buffer.name()));
		} catch (IllegalArgumentException iaex) {
			Log.log(Log.WARNING, MarkdownUtil.class, iaex.getMessage());
			target = MarkdownPlugin.Target.Buffer;
			jEdit.setProperty(MarkdownPlugin.OPTION_PREFIX + TARGET, target.name());
			Log.log(Log.NOTICE, MarkdownUtil.class, "Set target as '" + target + "'.");
		}
	}

	/**
	 * (Re)builds the flexmark parser/renderer pair for the given set of enabled extension names.
	 */
	private void build(final Set<String> names) {
		final MutableDataSet options = new MutableDataSet();
		final List<Extension> flexmarkExtensions = new ArrayList<Extension>();

		if (names.contains("tables")) {
			flexmarkExtensions.add(TablesExtension.create());
		}
		if (names.contains("autolinks")) {
			flexmarkExtensions.add(AutolinkExtension.create());
		}
		if (names.contains("abbreviations")) {
			flexmarkExtensions.add(AbbreviationExtension.create());
		}
		if (names.contains("quotes") || names.contains("smarts") || names.contains("smartypants")) {
			flexmarkExtensions.add(TypographicExtension.create());
		}
		flexmarkExtensions.add(MermaidExtension.create());
		options.set(Parser.EXTENSIONS, flexmarkExtensions);
		if (names.contains("hardwraps")) {
			options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");
		}
		if (names.contains("noHypertext")) {
			options.set(HtmlRenderer.SUPPRESS_HTML, true);
		} else {
			if (names.contains("noBlocks")) {
				options.set(HtmlRenderer.SUPPRESS_HTML_BLOCKS, true);
			}
			if (names.contains("noInline")) {
				options.set(HtmlRenderer.SUPPRESS_INLINE_HTML, true);
			}
		}
		parser = Parser.builder(options).build();
		renderer = HtmlRenderer.builder(options).build();
	}

	private Set<String> readExtensions() {
		final Set<String> names = new LinkedHashSet<String>();

		if (jEdit.getBooleanProperty(MarkdownPlugin.OPTION_PREFIX + NONE_EXTENSIONS, false)) {
			return names;
		}
		if (jEdit.getBooleanProperty(MarkdownPlugin.OPTION_PREFIX + ALL_EXTENSIONS, true)) {
			names.addAll(Arrays.asList(ALL_EXTENSION_NAME));
			return names;
		}
		for (String name : EXTENSION_NAME) {
			if (jEdit.getBooleanProperty(MarkdownPlugin.OPTION_PREFIX + name, false)) {
				names.add(name);
			}
		}

		return names;
	}

	private synchronized Set<String> saveExtensions(final Set<String> names) {
		final List<String> known = Arrays.asList(EXTENSION_NAME);
		final Set<String> valid = new LinkedHashSet<String>();

		for (String name : names) {
			if (known.contains(name)) {
				valid.add(name);
			}
		}

		final boolean isNone = valid.isEmpty();
		final boolean isAll = valid.size() == EXTENSION_NAME.length;

		jEdit.setBooleanProperty(MarkdownPlugin.OPTION_PREFIX + NONE_EXTENSIONS, isNone);
		jEdit.setBooleanProperty(MarkdownPlugin.OPTION_PREFIX + ALL_EXTENSIONS, isAll);
		for (String name : EXTENSION_NAME) {
			jEdit.setBooleanProperty(MarkdownPlugin.OPTION_PREFIX + name, valid.contains(name));
		}

		return valid;
	}

}
