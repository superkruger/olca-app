package org.openlca.app.analysis;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.FormPage;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.openlca.app.App;
import org.openlca.app.components.FlowImpactSelection;
import org.openlca.app.components.FlowImpactSelection.EventHandler;
import org.openlca.app.db.Database;
import org.openlca.app.html.HtmlPage;
import org.openlca.app.html.IHtmlResource;
import org.openlca.app.util.UI;
import org.openlca.core.editors.HtmlView;
import org.openlca.core.model.Flow;
import org.openlca.core.model.descriptors.ImpactCategoryDescriptor;
import org.openlca.core.results.AnalysisResult;
import org.openlca.core.results.ContributionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

public class SunBurstView extends FormPage implements HtmlPage {

	private Logger log = LoggerFactory.getLogger(getClass());
	private AnalysisResult result;
	private Browser browser;
	private FlowImpactSelection flowImpactSelection;

	public SunBurstView(AnalyzeEditor editor, AnalysisResult result) {
		super(editor, "analysis.SunBurstView", "Sun burst");
		this.result = result;
	}

	@Override
	public IHtmlResource getResource() {
		return HtmlView.SUNBURST_CHART.getResource();
	}

	@Override
	public void onLoaded() {
		App.run("Calculate result", new Runnable() {
			@Override
			public void run() {
				createCalculator();
			}
		}, new Runnable() {
			@Override
			public void run() {
				if (calculator == null)
					return;
				Flow[] flows = result.getFlowIndex().getFlows();
				if (flows == null || flows.length == 0)
					return;
				flowImpactSelection.selectWithEvent(flows[0]);
			}
		});
	}

	@Override
	protected void createFormContent(IManagedForm managedForm) {
		ScrolledForm form = UI.formHeader(managedForm, "Sun burst");
		FormToolkit toolkit = managedForm.getToolkit();
		Composite body = UI.formBody(form, toolkit);
		Composite composite = toolkit.createComposite(body);
		UI.gridLayout(composite, 2);
		flowImpactSelection = FlowImpactSelection.onDatabase(Database.get())
				.withAnalysisResult(result)
				.withEventHandler(new SelectionHandler())
				.create(composite, toolkit);
		browser = UI.createBrowser(body, this);
		UI.gridData(browser, true, true);
		form.reflow(true);
	}

	private class SelectionHandler implements EventHandler {
		@Override
		public void flowSelected(Flow flow) {
			if (calculator == null || flow == null)
				return;
			ContributionTree tree = calculator.calculate(flow);
			setResultData(tree);
		}

		@Override
		public void impactCategorySelected(
				ImpactCategoryDescriptor impactCategory) {
			if (calculator == null || impactCategory == null)
				return;
			ContributionTree tree = calculator.calculate(impactCategory);
			setResultData(tree);
		}

		private void setResultData(ContributionTree tree) {
			Gson gson = new Gson();
			String json = gson.toJson(tree);
			String command = "setData(" + json + ")";
			try {
				browser.evaluate(command);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

}