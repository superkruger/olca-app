package org.openlca.app.editors.graphical.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.draw2d.ColorConstants;
import org.eclipse.draw2d.CompoundBorder;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.GridData;
import org.eclipse.draw2d.GridLayout;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.openlca.app.M;
import org.openlca.app.editors.graphical.command.ChangeStateCommand;
import org.openlca.app.editors.graphical.layout.Animation;
import org.openlca.app.editors.graphical.model.ProcessExpander.Side;
import org.openlca.app.rcp.images.Icon;
import org.openlca.app.util.Labels;
import org.openlca.core.model.ProcessType;
import org.openlca.core.model.descriptors.CategorizedDescriptor;
import org.openlca.core.model.descriptors.ProcessDescriptor;
import org.openlca.core.model.descriptors.ProductSystemDescriptor;

class ProcessFigure extends Figure {

	static final int MINIMUM_HEIGHT = 25;
	static final int MINIMUM_WIDTH = 175;
	static final int MARGIN_HEIGHT = 2;
	static final int MARGIN_WIDTH = 4;
	private static final int TEXT_HEIGHT = 16;
	private static final Color LINE_COLOR = ColorConstants.gray;
	private static final Color TEXT_COLOR = ColorConstants.black;

	final ProcessNode node;
	private ProcessExpander leftExpander;
	private ProcessExpander rightExpander;
	private int minimumHeight = 0;

	ProcessFigure(ProcessNode node) {
		this.node = node;
		initializeFigure();
		createHeader();
		addMouseListener(new DoubleClickListener());
	}

	private void initializeFigure() {
		String tooltip = null;
		if (node.process instanceof ProcessDescriptor) {
			ProcessDescriptor d = (ProcessDescriptor) node.process;
			tooltip = Labels.processType(d.processType)
					+ ": " + node.getName();
		} else {
			tooltip = M.ProductSystem + ": " + node.getName();
		}
		setToolTip(new Label(tooltip));
		setForegroundColor(TEXT_COLOR);
		setBounds(new Rectangle(0, 0, 0, 0));
		setSize(calculateSize());
		GridLayout layout = new GridLayout(1, true);
		layout.horizontalSpacing = 10;
		layout.verticalSpacing = 0;
		layout.marginHeight = MARGIN_HEIGHT;
		layout.marginWidth = MARGIN_WIDTH;
		setLayoutManager(layout);
		paintBorder();
	}

	private void createHeader() {
		Figure top = new Figure();
		GridLayout topLayout = new GridLayout(3, false);
		topLayout.horizontalSpacing = 0;
		topLayout.verticalSpacing = 0;
		topLayout.marginHeight = 0;
		topLayout.marginWidth = 0;
		top.setLayoutManager(topLayout);
		leftExpander = new ProcessExpander(node, Side.INPUT);
		rightExpander = new ProcessExpander(node, Side.OUTPUT);
		top.add(leftExpander, new GridData(SWT.LEFT, SWT.CENTER, false, false));
		top.add(new Label(node.getName()), new GridData(SWT.FILL, SWT.FILL, true, false));
		top.add(rightExpander, new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		add(top, new GridData(SWT.FILL, SWT.FILL, true, false));
		GridData dummyGridData = new GridData(SWT.FILL, SWT.FILL, true, false);
		dummyGridData.heightHint = TEXT_HEIGHT + 3 * MARGIN_HEIGHT;
		add(new Figure(), dummyGridData);
	}

	private IOFigure getIOFigure() {
		for (Object child : getChildren())
			if (child instanceof IOFigure)
				return (IOFigure) child;
		return null;
	}

	void refresh() {
		leftExpander.refresh();
		rightExpander.refresh();
		int x = getLocation().x;
		int y = getLocation().y;
		Dimension p = calculateSize();
		int width = p.width;
		int height = p.height;
		if (getSize().width > width)
			width = getSize().width;
		if (getParent() == null)
			return;
		getParent().setConstraint(this, new Rectangle(x - 1, y - 1, width, height));
		for (Link link : node.links)
			if (node.equals(link.inputNode))
				link.refreshTargetAnchor();
			else if (node.equals(link.outputNode))
				link.refreshSourceAnchor();
	}

	@Override
	protected void paintFigure(Graphics g) {
		g.pushState();
		g.setBackgroundColor(ColorConstants.white);
		g.fillRectangle(new Rectangle(getLocation(), getSize()));
		paintTop(g);
		if (!node.isMinimized() || Animation.isRunning())
			paintTable(g);
		g.popState();
		super.paintFigure(g);
	}

	private void paintBorder() {
		if (isLCI()) {
			LineBorder outer = new LineBorder(LINE_COLOR, 1);
			LineBorder innerInner = new LineBorder(LINE_COLOR, 1);
			LineBorder innerOuter = new LineBorder(ColorConstants.white, 1);
			CompoundBorder inner = new CompoundBorder(innerOuter, innerInner);
			CompoundBorder border = new CompoundBorder(outer, inner);
			setBorder(border);
		} else {
			LineBorder border = new LineBorder(LINE_COLOR, 1);
			setBorder(border);
		}
	}

	private void paintTop(Graphics graphics) {
		Image file = null;
		if (node.isMarked())
			file = Icon.PROCESS_BG_MARKED.get();
		if (node.process instanceof ProductSystemDescriptor)
			file = Icon.PROCESS_BG_SYS.get();
		else if (isLCI())
			file = Icon.PROCESS_BG_LCI.get();
		else
			file = Icon.PROCESS_BG.get();
		int x = getLocation().x;
		int y = getLocation().y;
		int width = getSize().width;
		for (int i = 0; i < width - 20; i++)
			graphics.drawImage(file, new Point(x + i, y));
		graphics.setForegroundColor(LINE_COLOR);
		graphics.drawLine(new Point(x, y + MINIMUM_HEIGHT), new Point(x + width - 1, y + MINIMUM_HEIGHT));
		graphics.setForegroundColor(TEXT_COLOR);
	}

	private void paintTable(Graphics graphics) {
		graphics.setForegroundColor(LINE_COLOR);
		int margin = 5;
		int width = getSize().width;
		int height = getSize().height;
		int x = getLocation().x;
		int y = getLocation().y;
		graphics.drawLine(new Point(x + margin, y + MINIMUM_HEIGHT
				+ TEXT_HEIGHT + MARGIN_HEIGHT), new Point(x + width - margin,
						y
								+ MINIMUM_HEIGHT + TEXT_HEIGHT + MARGIN_HEIGHT));
		if (height - margin > MINIMUM_HEIGHT + margin)
			graphics.drawLine(new Point(x + width / 2, y + MINIMUM_HEIGHT + margin), new Point(x + width / 2, y
					+ height - margin));
		graphics.setForegroundColor(TEXT_COLOR);
		graphics.drawText(M.Inputs, new Point(x + width / 6, y + MINIMUM_HEIGHT + MARGIN_HEIGHT));
		graphics.drawText(M.Outputs, new Point(x + 2 * width / 3, y + MINIMUM_HEIGHT + MARGIN_HEIGHT));
		graphics.setForegroundColor(ColorConstants.black);
	}

	@Override
	protected void paintChildren(Graphics graphics) {
		super.paintChildren(graphics);
		if (getIOFigure() == null)
			return;
		Rectangle clip = Rectangle.SINGLETON;
		if (getIOFigure().isVisible() && getIOFigure().intersects(graphics.getClip(clip))) {
			graphics.clipRect(getIOFigure().getBounds());
			getIOFigure().paint(graphics);
			graphics.restoreState();
		}
	}

	ProcessExpander getLeftExpander() {
		return leftExpander;
	}

	ProcessExpander getRightExpander() {
		return rightExpander;
	}

	ExchangeFigure[] getExchangeFigures() {
		List<ExchangeFigure> figures = new ArrayList<>();
		for (ExchangeFigure o2 : getIOFigure().getChildren())
			figures.add(o2);
		ExchangeFigure[] result = new ExchangeFigure[figures.size()];
		figures.toArray(result);
		return result;
	}

	@Override
	public Dimension getPreferredSize(int hint, int hint2) {
		final Dimension cSize = calculateSize();
		if (cSize.height > getSize().height || cSize.width > getSize().width || node.isMinimized())
			return cSize;
		return getSize();
	}

	Dimension calculateSize() {
		int offSet = 0;
		if (isLCI())
			offSet = 3;
		int x = MINIMUM_WIDTH + offSet;
		if (getSize() != null && getSize().width > x)
			x = getSize().width;
		int y = MINIMUM_HEIGHT + offSet;
		if (node != null && !node.isMinimized())
			y = getMinimumHeight();
		return new Dimension(x, y);
	}

	int getMinimumHeight() {
		if (minimumHeight == 0)
			initializeMinimumHeight();
		return minimumHeight;
	}

	private void initializeMinimumHeight() {
		int inputs = 0;
		int outputs = 0;
		for (ExchangeNode e : node.getChildren().get(0).getChildren())
			if (!e.isDummy())
				if (e.exchange.isInput)
					inputs++;
				else
					outputs++;
		int length = Math.max(inputs, outputs);
		int offSet = 0;
		if (isLCI())
			offSet = 3;
		int startExchanges = MINIMUM_HEIGHT + 4 * MARGIN_HEIGHT + TEXT_HEIGHT + offSet;
		minimumHeight = startExchanges + length * (TEXT_HEIGHT + 1);
	}

	private boolean isLCI() {
		CategorizedDescriptor d = node.process;
		if (d instanceof ProductSystemDescriptor)
			return true;
		if (!(d instanceof ProcessDescriptor))
			return false;
		ProcessDescriptor p = (ProcessDescriptor) d;
		return p.processType == ProcessType.LCI_RESULT;
	}

	private class DoubleClickListener implements MouseListener {

		private boolean firstClick = true;

		@Override
		public void mouseDoubleClicked(MouseEvent arg0) {
		}

		@Override
		public void mousePressed(MouseEvent e) {
			if (e.button == 1) {
				if (firstClick) {
					firstClick = false;
					TimerTask timerTask = new TimerTask() {

						@Override
						public void run() {
							firstClick = true;
						}

					};
					Timer timer = new Timer();
					timer.schedule(timerTask, 250);
				} else {
					ChangeStateCommand command = new ChangeStateCommand(node);
					node.parent().editor.getCommandStack().execute(command);
				}
			}
		}

		@Override
		public void mouseReleased(MouseEvent me) {
		}

	}

}
