package org.openlca.app.tools.mapping;

import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.FormPage;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.eclipse.ui.forms.widgets.Section;
import org.openlca.app.App;
import org.openlca.app.M;
import org.openlca.app.rcp.images.Icon;
import org.openlca.app.util.Actions;
import org.openlca.app.util.Controls;
import org.openlca.app.util.UI;
import org.openlca.app.util.tables.Tables;
import org.openlca.app.util.viewers.Viewers;
import org.openlca.io.maps.FlowMapEntry;
import org.openlca.io.maps.FlowRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MappingPage extends FormPage {

	private final MappingTool tool;
	TableViewer table;

	public MappingPage(MappingTool tool) {
		super(tool, "MappingPage", "Flow mapping");
		this.tool = tool;
	}

	@Override
	protected void createFormContent(IManagedForm mform) {
		ScrolledForm form = UI.formHeader(mform, "Flow mapping");
		FormToolkit tk = mform.getToolkit();
		Composite body = UI.formBody(form, tk);
		createInfoSection(tk, body);
		createTable(body, tk);
		form.reflow(true);
	}

	private void createInfoSection(FormToolkit tk, Composite body) {
		Composite comp = UI.formSection(body, tk, M.GeneralInformation);
		Text name = UI.formText(comp, tk, M.Name);
		Controls.set(name, this.tool.mapping.name);

		UI.formLabel(comp, tk, "Source system");
		ProviderRow sourceRow = new ProviderRow(comp, tk);
		sourceRow.onSelect = p -> tool.sourceSystem = p;

		UI.formLabel(comp, tk, "Target system");
		ProviderRow targetRow = new ProviderRow(comp, tk);
		targetRow.onSelect = p -> tool.targetSystem = p;

		UI.filler(comp);
		Button checkButton = tk.createButton(comp, "Check mappings", SWT.NONE);
		checkButton.setImage(Icon.ACCEPT.get());
		Controls.onSelect(checkButton, _e -> {
			App.runWithProgress("Check mappings",
					this::syncMappings,
					() -> table.setInput(tool.mapping.entries));
		});
	}

	private void createTable(Composite body, FormToolkit tk) {
		Section section = UI.section(body, tk, "Flow mapping");
		UI.gridData(section, true, true);
		Composite comp = UI.sectionClient(section, tk);
		UI.gridLayout(comp, 1);
		table = Tables.createViewer(
				comp,
				"Status",
				"Source flow",
				"Source category",
				"Target flow",
				"Target category",
				"Conversion factor",
				"Default provider");
		table.setLabelProvider(new TableLabel());
		double w = 1.0 / 7.0;
		Tables.bindColumnWidths(table, w, w, w, w, w, w, w);
		table.setInput(this.tool.mapping.entries);
		bindActions(section, table);
	}

	private void bindActions(Section section, TableViewer table) {
		Action add = Actions.onAdd(() -> {
			FlowMapEntry e = new FlowMapEntry();
			if (Dialog.OK != MappingDialog.open(tool, e))
				return;
			tool.mapping.entries.add(e);
			table.refresh();
		});

		Action edit = Actions.onEdit(() -> {
			FlowMapEntry e = Viewers.getFirstSelected(table);
			if (e == null)
				return;
			if (Dialog.OK == MappingDialog.open(tool, e)) {
				table.refresh();
			}
		});
		Tables.onDoubleClick(table, _e -> edit.run());

		Action delete = Actions.onRemove(() -> {
			List<FlowMapEntry> entries = Viewers.getAllSelected(table);
			if (entries.isEmpty())
				return;
			tool.mapping.entries.removeAll(entries);
			table.refresh();
		});

		Actions.bind(section, add, edit, delete);
		Actions.bind(table, add, edit, delete);
	}

	private void syncMappings() {
		// run the sync functions in two separate threads and wait for
		// them to finish
		Thread st = null;
		Thread tt = null;
		if (tool.sourceSystem != null) {
			Stream<FlowRef> stream = tool.mapping.entries
					.stream().map(e -> e.sourceFlow);
			st = new Thread(
					() -> tool.sourceSystem.sync(stream));
		}
		if (tool.targetSystem != null) {
			Stream<FlowRef> stream = tool.mapping.entries
					.stream().map(e -> e.targetFlow);
			tt = new Thread(
					() -> tool.targetSystem.sync(stream));
		}
		if (st == null && tt == null)
			return;
		try {
			if (st != null) {
				st.run();
				st.join();
			}
			if (tt != null) {
				tt.run();
				tt.join();
			}
		} catch (Exception e) {
			Logger log = LoggerFactory.getLogger(getClass());
			log.error("Failed to sync flow mappings", e);
		}
	}
}