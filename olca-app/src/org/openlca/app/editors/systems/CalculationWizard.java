package org.openlca.app.editors.systems;

import java.lang.reflect.InvocationTargetException;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.ui.IEditorPart;
import org.openlca.app.App;
import org.openlca.app.M;
import org.openlca.app.Preferences;
import org.openlca.app.db.Cache;
import org.openlca.app.db.Database;
import org.openlca.app.editors.Editors;
import org.openlca.app.editors.ModelEditorInput;
import org.openlca.app.results.ResultEditorInput;
import org.openlca.app.results.analysis.AnalyzeEditor;
import org.openlca.app.results.quick.QuickResultEditor;
import org.openlca.app.results.regionalized.RegionalizedResultEditor;
import org.openlca.app.results.simulation.SimulationEditor;
import org.openlca.app.util.Info;
import org.openlca.app.util.Question;
import org.openlca.app.util.UI;
import org.openlca.core.database.FlowDao;
import org.openlca.core.database.ProductSystemDao;
import org.openlca.core.math.CalculationSetup;
import org.openlca.core.math.CalculationType;
import org.openlca.core.math.SystemCalculator;
import org.openlca.core.math.data_quality.AggregationType;
import org.openlca.core.math.data_quality.DQCalculationSetup;
import org.openlca.core.math.data_quality.DQResult;
import org.openlca.core.math.data_quality.ProcessingType;
import org.openlca.core.model.AllocationMethod;
import org.openlca.core.model.Exchange;
import org.openlca.core.model.Flow;
import org.openlca.core.model.ModelType;
import org.openlca.core.model.ProductSystem;
import org.openlca.core.model.descriptors.BaseDescriptor;
import org.openlca.core.model.descriptors.FlowDescriptor;
import org.openlca.core.results.ContributionResult;
import org.openlca.core.results.FullResult;
import org.openlca.core.results.SimpleResult;
import org.openlca.geo.RegionalizedCalculator;
import org.openlca.geo.RegionalizedResult;
import org.openlca.geo.parameter.ParameterSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wizard for setting calculation properties and run the calculation of a
 * product system
 */
public class CalculationWizard extends Wizard {

	private Logger log = LoggerFactory.getLogger(this.getClass());

	ProductSystem productSystem;

	private CalculationWizardPage page;
	private DQSettingsPage dqPage;

	public CalculationWizard(ProductSystem productSystem) {
		this.productSystem = productSystem;
		setNeedsProgressMonitor(true);
		setWindowTitle(M.CalculationProperties);
	}

	public static void open(ProductSystem productSystem) {
		if (productSystem == null)
			return;
		boolean doContinue = checkForUnsavedContent(productSystem);
		if (!doContinue)
			return;
		CalculationWizard wizard = new CalculationWizard(productSystem);
		WizardDialog dialog = new WizardDialog(UI.shell(), wizard);
		dialog.open();
	}

	private static boolean checkForUnsavedContent(ProductSystem system) {
		IEditorPart[] dirty = Editors.getActivePage().getDirtyEditors();
		if (dirty.length == 0)
			return true;
		List<IEditorPart> relevant = new ArrayList<>();
		for (IEditorPart part : dirty) {
			if (!(part.getEditorInput() instanceof ModelEditorInput))
				continue;
			ModelEditorInput input = (ModelEditorInput) part.getEditorInput();
			ModelType type = input.getDescriptor().type;
			if (type == ModelType.PROJECT || type == ModelType.ACTOR || type == ModelType.SOURCE)
				continue;
			if (type == ModelType.PRODUCT_SYSTEM && input.getDescriptor().id != system.id)
				continue;
			relevant.add(part);
		}
		if (relevant.isEmpty())
			return true;
		int answer = Question.askWithCancel(M.UnsavedChanges, M.SomeElementsAreNotSaved);
		if (answer == IDialogConstants.NO_ID)
			return true;
		if (answer == IDialogConstants.CANCEL_ID)
			return false;
		for (IEditorPart part : relevant) {
			Editors.getActivePage().saveEditor(part, false);
		}
		return true;
	}

	@Override
	public void addPages() {
		page = new CalculationWizardPage();
		addPage(page);
		dqPage = new DQSettingsPage();
		addPage(dqPage);
	}

	@Override
	public boolean performFinish() {
		CalculationSetup setup = page.getSetup(productSystem);
		DQCalculationSetup dqSetup = null;
		if (page.doDqAssessment())
			dqSetup = dqPage.getSetup(productSystem);
		boolean storeInventoryResult = page.doStoreInventoryResult();
		saveDefaults(setup, dqSetup);
		try {
			Calculation calculation = new Calculation(setup, dqSetup, storeInventoryResult);
			getContainer().run(true, true, calculation);
			if (calculation.outOfMemory)
				MemoryError.show();
			return !calculation.outOfMemory;
		} catch (Exception e) {
			log.error("Calculation failed", e);
			return false;
		}
	}

	private ResultEditorInput getEditorInput(Object result,
			CalculationSetup setup, ParameterSet parameterSet,
			DQResult dqResult) {
		ResultEditorInput input = ResultEditorInput.create(setup, result)
				.with(dqResult).with(parameterSet);
		return input;
	}

	private void saveDefaults(CalculationSetup setup, DQCalculationSetup dqSetup) {
		if (setup == null)
			return;
		AllocationMethod am = setup.allocationMethod;
		String amVal = am == null ? "NONE" : am.name();
		Preferences.set("calc.allocation.method", amVal);
		BaseDescriptor m = setup.impactMethod;
		String mVal = m == null ? "" : m.refId;
		Preferences.set("calc.impact.method", mVal);
		BaseDescriptor nws = setup.nwSet;
		String nwsVal = nws == null ? "" : nws.refId;
		Preferences.set("calc.nwset", nwsVal);
		saveDefault(CalculationType.class, setup.type);
		Preferences.set("calc.numberOfRuns", Integer.toString(setup.numberOfRuns));
		Preferences.set("calc.costCalculation", Boolean.toString(setup.withCosts));
		if (dqSetup == null) {
			Preferences.set("calc.dqAssessment", "false");
			return;
		}
		Preferences.set("calc.dqAssessment", "true");
		saveDefault(AggregationType.class, dqSetup.aggregationType);
		saveDefault(ProcessingType.class, dqSetup.processingType);
		saveDefault(RoundingMode.class, dqSetup.roundingMode);
	}

	private <T extends Enum<T>> void saveDefault(Class<T> clazz, T value) {
		Preferences.set("calc." + clazz.getSimpleName(), value == null ? null : value.name());
	}

	private class Calculation implements IRunnableWithProgress {

		private CalculationSetup setup;
		private DQCalculationSetup dqSetup;
		private boolean storeInventoryResult;
		private boolean outOfMemory;

		public Calculation(CalculationSetup setup, DQCalculationSetup dqSetup,
				boolean storeInventoryResult) {
			this.setup = setup;
			this.dqSetup = dqSetup;
			this.storeInventoryResult = storeInventoryResult;
		}

		@Override
		public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
			outOfMemory = false;
			monitor.beginTask(M.RunCalculation, IProgressMonitor.UNKNOWN);
			int size = productSystem.processes.size();
			log.trace("calculate a {} x {} system", size, size);
			try {
				switch (setup.type) {
				case UPSTREAM_ANALYSIS:
					analyse();
					break;
				case MONTE_CARLO_SIMULATION:
					SimulationEditor.open(setup, Cache.getMatrixCache());
					break;
				case CONTRIBUTION_ANALYSIS:
					solve();
					break;
				case REGIONALIZED_CALCULATION:
					calcRegionalized();
					break;
				default:
					break;
				}
			} catch (OutOfMemoryError e) {
				outOfMemory = true;
			}
			monitor.done();
		}

		private void analyse() {
			log.trace("run analysis");
			SystemCalculator calculator = new SystemCalculator(Cache.getMatrixCache(), App.getSolver());
			FullResult result = calculator.calculateFull(setup);
			log.trace("calculation done, open editor");
			setInventory(result);
			DQResult dqResult = DQResult.calculate(Database.get(), result, dqSetup);
			ResultEditorInput input = getEditorInput(result, setup, null, dqResult);
			Editors.open(input, AnalyzeEditor.ID);
		}

		private void solve() {
			log.trace("run quick calculation");
			SystemCalculator calculator = new SystemCalculator(Cache.getMatrixCache(), App.getSolver());
			ContributionResult result = calculator.calculateContributions(setup);
			log.trace("calculation done, open editor");
			setInventory(result);
			DQResult dqResult = DQResult.calculate(Database.get(), result, dqSetup);
			ResultEditorInput input = getEditorInput(result, setup, null, dqResult);
			Editors.open(input, QuickResultEditor.ID);
		}

		private void calcRegionalized() {
			log.trace("calculate regionalized result");
			RegionalizedCalculator calc = new RegionalizedCalculator(setup, App.getSolver());
			RegionalizedResult result = calc.calculate(Database.get(), Cache.getMatrixCache());
			if (result == null) {
				Info.showBox(M.NoRegionalizedInformation_Message);
				return;
			}
			setInventory(result.result);
			DQResult dqResult = DQResult.calculate(Database.get(), result.result, dqSetup);
			ResultEditorInput input = getEditorInput(result, setup, result.parameterSet, dqResult);
			Editors.open(input, RegionalizedResultEditor.ID);
		}

		private void setInventory(SimpleResult result) {
			if (!storeInventoryResult)
				return;
			productSystem.inventory.clear();
			Map<Long, Flow> flows = new HashMap<>();
			Set<Long> ids = new HashSet<>();
			for (FlowDescriptor flow : result.getFlows()) {
				ids.add(flow.id);
			}
			for (Flow flow : new FlowDao(Database.get()).getForIds(ids)) {
				flows.put(flow.id, flow);
			}
			for (FlowDescriptor d : result.getFlows()) {
				Flow flow = flows.get(d.id);
				Exchange exchange = Exchange.from(flow);
				exchange.amount = result.getTotalFlowResult(d);
				exchange.isInput = result.isInput(d);
				productSystem.inventory.add(exchange);
			}
			productSystem = new ProductSystemDao(Database.get()).update(productSystem);
		}

	}
}
