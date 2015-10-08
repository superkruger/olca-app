package org.openlca.app.wizards.io;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.IExportWizard;
import org.eclipse.ui.IWorkbench;
import org.openlca.app.Messages;
import org.openlca.app.db.Database;
import org.openlca.core.database.IDatabase;
import org.openlca.core.model.CategorizedEntity;
import org.openlca.core.model.ModelType;
import org.openlca.core.model.descriptors.BaseDescriptor;
import org.openlca.io.ilcd.ILCDExport;

/**
 * Wizard for exporting processes, flows, flow properties and unit group to the
 * ILCD format
 */
public class ILCDExportWizard extends Wizard implements IExportWizard {

	private ModelSelectionPage exportPage;

	public ILCDExportWizard() {
		setNeedsProgressMonitor(true);
	}

	@Override
	public void addPages() {
		ModelType[] types = {
				ModelType.IMPACT_METHOD, ModelType.PROCESS, ModelType.FLOW,
				ModelType.FLOW_PROPERTY, ModelType.UNIT_GROUP, ModelType.ACTOR,
				ModelType.SOURCE
		};
		exportPage = new ModelSelectionPage(types);
		addPage(exportPage);
	}

	@Override
	public void init(IWorkbench workbench, IStructuredSelection selection) {
		setWindowTitle(Messages.ExportILCD);
	}

	@Override
	public boolean performFinish() {
		IDatabase database = Database.get();
		if (database == null)
			return false;
		File targetDir = exportPage.getExportDestination();
		if (targetDir == null || !targetDir.isDirectory())
			return false;
		List<BaseDescriptor> descriptors = exportPage.getSelectedModels();

		boolean errorOccured = false;
		try {
			getContainer().run(true, true, monitor -> {
				monitor.beginTask(Messages.Export, descriptors.size());
				int worked = 0;
				ILCDExport export = new ILCDExport(targetDir);
				for (BaseDescriptor descriptor : descriptors) {
					if (monitor.isCanceled())
						break;
					monitor.setTaskName(descriptor.getName());
					try {
						Object component = database.createDao(
								descriptor.getModelType().getModelClass())
								.getForId(descriptor.getId());
						if (component instanceof CategorizedEntity)
							export.export((CategorizedEntity) component,
									database);
					} catch (Exception e) {
						throw new InvocationTargetException(e);
					} finally {
						monitor.worked(++worked);
					}
				}
				export.close();
			});

		} catch (Exception e) {
			errorOccured = true;
		}

		return !errorOccured;
	}

}
