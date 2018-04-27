package org.openlca.app.editors.graphical.action;

import org.eclipse.jface.viewers.ISelection;
import org.openlca.app.M;
import org.openlca.app.rcp.images.Icon;
import org.openlca.app.util.Question;

class MassExpansionAction extends EditorAction {

	static final int EXPAND = 1;
	static final int COLLAPSE = 2;
	private final int type;

	MassExpansionAction(int type) {
		if (type == EXPAND) {
			setId(ActionIds.EXPAND_ALL);
			setText(M.ExpandAll);
			setImageDescriptor(Icon.EXPAND.descriptor());
		} else if (type == COLLAPSE) {
			setId(ActionIds.COLLAPSE_ALL);
			setText(M.CollapseAll);
			setImageDescriptor(Icon.COLLAPSE.descriptor());
		}
		this.type = type;
	}

	@Override
	public void run() {
		if (type == EXPAND) {
			if (areYouSure())
				editor.expand();
		} else if (type == COLLAPSE)
			editor.collapse();
	}

	private boolean areYouSure() {
		int amount = editor.getModel().getProductSystem().processes.size();
		if (amount < 500)
			return true;
		String title = M.ExpandAll;
		String text = M.ExpandAll + ": " + amount + " "
				+ M.Processes;
		return Question.ask(title, text);
	}

	@Override
	protected boolean accept(ISelection selection) {
		return true;
	}

}
