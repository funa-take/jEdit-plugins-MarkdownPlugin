package ua.pico.jedit.markdown;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataHolder;

import java.util.HashSet;
import java.util.Set;

/**
 * flexmark extension that renders ```mermaid fenced code blocks as
 * &lt;pre class="mermaid"&gt; with unescaped content, so mermaid.js can
 * pick them up. Any other fenced code block is left to the core renderer.
 */
public class MermaidExtension implements HtmlRenderer.HtmlRendererExtension {

	private static final String INFO = "mermaid";

	public static MermaidExtension create() {
		return new MermaidExtension();
	}

	public void rendererOptions(final MutableDataHolder options) {
	}

	public void extend(final HtmlRenderer.Builder builder, final String rendererType) {
		builder.nodeRendererFactory(new NodeRendererFactory() {
			public NodeRenderer apply(final DataHolder options) {
				return new MermaidNodeRenderer();
			}
		});
	}

	private static class MermaidNodeRenderer implements NodeRenderer {

		public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
			final Set<NodeRenderingHandler<?>> handlers = new HashSet<NodeRenderingHandler<?>>();

			handlers.add(new NodeRenderingHandler<FencedCodeBlock>(FencedCodeBlock.class, new NodeRenderingHandler.CustomNodeRenderer<FencedCodeBlock>() {
				public void render(final FencedCodeBlock node, final NodeRendererContext context, final HtmlWriter html) {
					if (INFO.equals(node.getInfo().toString())) {
						html.raw("<pre class=\"mermaid\">");
						html.raw(node.getContentChars().toString());
						html.raw("</pre>\n");
					} else {
						context.delegateRender();
					}
				}
			}));

			return handlers;
		}
	}

}
