package ua.pico.jedit.markdown;

import org.gjt.sp.jedit.AbstractOptionPane;
import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.util.Log;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class MarkdownOptionPane extends AbstractOptionPane implements ChangeListener, ActionListener {

	static final String PREVIEW_CSS = "preview.css";

	public MarkdownOptionPane() {
		super(MarkdownPlugin.NAME);

		final String label = ".label";
		final ButtonGroup targetGroup = new ButtonGroup();
		final ButtonGroup markdownGroup = new ButtonGroup();

		markdownUtil = MarkdownUtil.getInstance();
		
		// View options
		addSeparator("options.markdown.target.label");
		bufferButton = new JRadioButton(jEdit.getProperty("options.markdown.target.buffer.label"));
		clipboardButton = new JRadioButton(jEdit.getProperty("options.markdown.target.clipboard.label"));
		browserButton = new JRadioButton(jEdit.getProperty("options.markdown.target.browser.label"));
		targetGroup.add(bufferButton);
		targetGroup.add(clipboardButton);
		targetGroup.add(browserButton);
		addComponent(bufferButton);
		addComponent(clipboardButton);
		addComponent(browserButton);
		// Markdown options
		addSeparator("options.markdown.extensions.label");
		noneButton = new JRadioButton(jEdit.getProperty("options.markdown.none.label"));
		allButton = new JRadioButton(jEdit.getProperty("options.markdown.all.label"));
		chooseButton = new JRadioButton(jEdit.getProperty("options.markdown.choose.label"));
		markdownGroup.add(noneButton);
		markdownGroup.add(allButton);
		markdownGroup.add(chooseButton);
		addComponent(noneButton);
		addComponent(allButton);
		addComponent(chooseButton);
		noneButton.setSelected(true);
		// Markdown extensions
		extensions = new JCheckBox[MarkdownUtil.EXTENSION_NAME.length];
		for (int i = 0; i < extensions.length; i++) {
			extensions[i] = new JCheckBox(jEdit.getProperty(MarkdownPlugin.OPTION_PREFIX + MarkdownUtil.EXTENSION_NAME[i] + label));
			extensions[i].setEnabled(false);
			addComponent(extensions[i]);
		}
		chooseButton.addChangeListener(this);
		// Preview CSS
		addSeparator("options.markdown.preview.css.label");
		cssArea = new JTextArea(6, 40);
		cssArea.setLineWrap(true);
		cssArea.setWrapStyleWord(true);
		addComponent(new JScrollPane(cssArea));
		resetCssButton = new JButton(jEdit.getProperty("options.markdown.preview.css.reset.label"));
		resetCssButton.addActionListener(this);
		addComponent(resetCssButton);
	}

	public void stateChanged(final ChangeEvent event) {
		if (chooseButton == event.getSource()) {
			if (chooseButton.isSelected()) {
				for (JCheckBox extension : extensions) {
					extension.setEnabled(true);
				}
			} else {
				for (JCheckBox extension : extensions) {
					extension.setEnabled(false);
				}
			}
		}
	}

	public void actionPerformed(final ActionEvent event) {
		if (resetCssButton == event.getSource()) {
			jEdit.resetProperty(MarkdownPlugin.OPTION_PREFIX + PREVIEW_CSS);
			cssArea.setText(jEdit.getProperty(MarkdownPlugin.OPTION_PREFIX + PREVIEW_CSS, ""));
		}
	}

	@Override
	protected void _init() {
		final Set<String> usedExtensions = markdownUtil.getExtensions();

		switch (markdownUtil.getTarget()) {
		case Clipboard:
			clipboardButton.setSelected(true);
			break;
		case Browser:
			browserButton.setSelected(true);
			break;
		case Buffer:
		default:
			bufferButton.setSelected(true);
		}
		if (usedExtensions.isEmpty()) {
			noneButton.setSelected(true);
		} else if (usedExtensions.equals(new LinkedHashSet<String>(Arrays.asList(MarkdownUtil.ALL_EXTENSION_NAME)))) {
			allButton.setSelected(true);
		} else {
			chooseButton.setSelected(true);
		}
		if (chooseButton.isSelected()) {
			for (int i = 0; i < MarkdownUtil.EXTENSION_NAME.length; i++) {
				if (usedExtensions.contains(MarkdownUtil.EXTENSION_NAME[i])) {
					extensions[i].setSelected(true);
				}
			}
		}
		cssArea.setText(jEdit.getProperty(MarkdownPlugin.OPTION_PREFIX + PREVIEW_CSS, ""));
	}

	@Override
	protected void _save() {
		final Set<String> usedExtensions = new LinkedHashSet<String>();

		if (clipboardButton.isSelected()) {
			markdownUtil.setTarget(MarkdownPlugin.Target.Clipboard);
		} else if (browserButton.isSelected()) {
			markdownUtil.setTarget(MarkdownPlugin.Target.Browser);
		} else {
			markdownUtil.setTarget(MarkdownPlugin.Target.Buffer);
		}
		if (allButton.isSelected()) {
			usedExtensions.addAll(Arrays.asList(MarkdownUtil.ALL_EXTENSION_NAME));
		} else if (chooseButton.isSelected()) {
			for (int i = 0; i < extensions.length; i++) {
				if (extensions[i].isSelected()) {
					usedExtensions.add(MarkdownUtil.EXTENSION_NAME[i]);
				}
			}
		}
		markdownUtil.setExtensions(usedExtensions);
		jEdit.setProperty(MarkdownPlugin.OPTION_PREFIX + PREVIEW_CSS, cssArea.getText());
	}

	private MarkdownUtil markdownUtil;
	private JRadioButton bufferButton;
	private JRadioButton clipboardButton;
	private JRadioButton browserButton;
	private JRadioButton noneButton;
	private JRadioButton allButton;
	private JRadioButton chooseButton;
	private JCheckBox extensions[];
	private JTextArea cssArea;
	private JButton resetCssButton;

}
