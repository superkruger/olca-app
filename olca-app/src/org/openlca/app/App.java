package org.openlca.app;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.IProgressService;
import org.openlca.app.cloud.ui.preferences.CloudPreference;
import org.openlca.app.db.Cache;
import org.openlca.app.db.Database;
import org.openlca.app.editors.Editors;
import org.openlca.app.editors.ModelEditorInput;
import org.openlca.app.rcp.RcpActivator;
import org.openlca.app.rcp.Workspace;
import org.openlca.core.matrix.solvers.DenseSolver;
import org.openlca.core.matrix.solvers.IMatrixSolver;
import org.openlca.core.matrix.solvers.JavaSolver;
import org.openlca.core.model.CategorizedEntity;
import org.openlca.core.model.RootEntity;
import org.openlca.core.model.descriptors.BaseDescriptor;
import org.openlca.core.model.descriptors.CategorizedDescriptor;
import org.openlca.core.model.descriptors.Descriptors;
import org.openlca.eigen.NativeLibrary;
import org.openlca.julia.Julia;
import org.openlca.julia.JuliaSolver;
import org.openlca.updates.script.CalculationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

	static Logger log = LoggerFactory.getLogger(App.class);

	private static IMatrixSolver solver;

	private App() {
	}

	public static IMatrixSolver getSolver() {
		if (solver != null)
			return solver;
		try {
			File dir = new File(Platform.getInstallLocation().getURL().toURI());
			if (Julia.loadFromDir(dir)) {
				solver = new JuliaSolver();
				log.info("Loaded Julia-BLAS solver as default matrix solver");
				return solver;
			}
			log.info("Julia libraries could not be loaded from {}", dir);
		} catch (Exception e) {
			log.error("Failed to load libraries from folder <openLCA>/julia");
		}

		// TODO: this is currently a fall back, we will remove the
		// olca-eigen package when olca-rust is stable on all platforms
		log.trace("Try init olca-eigen");
		try {
			NativeLibrary.loadFromDir(Workspace.getDir());
		} catch (UnsatisfiedLinkError e) {
		}
		log.trace("olca-eigen loaded: {}", NativeLibrary.isLoaded());
		if (!NativeLibrary.isLoaded()) {
			// TODO: show some message ...
			log.warn("could not load a high-performance"
					+ " library for calculations");
			solver = new JavaSolver();
			return solver;
		}
		solver = new DenseSolver();
		return solver;
	}

	public static CalculationContext getCalculationContext() {
		return new CalculationContext(Cache.getMatrixCache(),
				Cache.getEntityCache(), getSolver());
	}

	/**
	 * Returns the version of the openLCA application. If there is a version
	 * defined in the ini-file (-olcaVersion argument) this is returned.
	 * Otherwise the version of the application bundle is returned.
	 */
	public static String getVersion() {
		String version = CommandArgument.VERSION.getValue();
		if (version != null)
			return version;
		return RcpActivator.getDefault().getBundle().getVersion().toString();
	}

	public static boolean isCommentingEnabled() {
		return Database.isConnected() && CloudPreference.doDisplayComments();
	}

	/**
	 * Indicates if the application runs in developer mode (for activation of
	 * experimental features and development tools).
	 */
	public static boolean runsInDevMode() {
		String val = CommandArgument.DEV_MODE.getValue();
		if (val == null)
			return false;
		return val.equals("true");
	}

	public static void openEditor(CategorizedEntity model) {
		openEditor(Descriptors.toDescriptor(model));
	}

	public static void openEditor(CategorizedDescriptor d) {
		if (d == null || d.type == null) {
			log.error("model is null, could not open editor");
			return;
		}
		log.trace("open editor for {} ", d);
		String editorId = "editors." + d.type.getModelClass()
				.getSimpleName().toLowerCase();
		ModelEditorInput input = new ModelEditorInput(d);
		Editors.open(input, editorId);
	}

	public static void closeEditor(CategorizedEntity entity) {
		BaseDescriptor descriptor = Descriptors.toDescriptor(entity);
		closeEditor(descriptor);
	}

	public static void closeEditor(BaseDescriptor d) {
		IEditorReference ref = findEditor(d);
		if (ref == null)
			return;
		Editors.close(ref);
	}

	/**
	 * Returns true if the given data set is currently opened in an editor that
	 * has a dirty (= unsaved) state.
	 */
	public static boolean hasDirtyEditor(RootEntity e) {
		if (e == null)
			return false;
		return hasDirtyEditor(Descriptors.toDescriptor(e));
	}

	/**
	 * Returns true if the given data set is currently opened in an editor that
	 * has a dirty (= unsaved) state.
	 */
	public static boolean hasDirtyEditor(BaseDescriptor d) {
		IEditorReference ref = findEditor(d);
		if (ref == null)
			return false;
		return ref.isDirty();
	}

	private static IEditorReference findEditor(BaseDescriptor d) {
		if (d == null)
			return null;
		for (IEditorReference ref : Editors.getReferences()) {
			try {
				IEditorInput inp = ref.getEditorInput();
				if (!(inp instanceof ModelEditorInput))
					continue;
				ModelEditorInput minp = (ModelEditorInput) inp;
				if (Objects.equals(minp.getDescriptor(), d))
					return ref;
			} catch (Exception e) {
				log.error("editor search failed", e);
			}
		}
		return null;
	}

	public static Job runInUI(String name, Runnable runnable) {
		WrappedUIJob job = new WrappedUIJob(name, runnable);
		job.setUser(true);
		job.schedule();
		return job;
	}

	/**
	 * Wraps a runnable in a job and executes it using the Eclipse jobs
	 * framework. No UI access is allowed for the runnable.
	 */
	public static Job run(String name, Runnable runnable) {
		return run(name, runnable, null);
	}

	/**
	 * See {@link App#run(String, Runnable)}. Additionally, this method allows
	 * to give a callback which is executed in the UI thread when the runnable
	 * is finished.
	 */
	public static Job run(String name, Runnable runnable, Runnable callback) {
		WrappedJob job = new WrappedJob(name, runnable);
		if (callback != null)
			job.setCallback(callback);
		job.setUser(true);
		job.schedule();
		return job;
	}

	public static void runWithProgress(String name, Runnable runnable) {
		IProgressService progress = PlatformUI.getWorkbench()
				.getProgressService();
		try {
			progress.run(true, false, (monitor) -> {
				monitor.beginTask(name, IProgressMonitor.UNKNOWN);
				runnable.run();
				monitor.done();
			});
		} catch (InvocationTargetException | InterruptedException e) {
			log.error("Error while running progress " + name, e);
		}
	}

	public static void runWithProgress(String name, Runnable fn,
			Runnable callback) {
		IProgressService progress = PlatformUI.getWorkbench()
				.getProgressService();
		try {
			progress.run(true, false, m -> {
				m.beginTask(name, IProgressMonitor.UNKNOWN);
				fn.run();
				m.done();
				if (callback != null) {
					WrappedUIJob uiJob = new WrappedUIJob(name, callback);
					uiJob.schedule();
				}
			});
		} catch (InvocationTargetException | InterruptedException e) {
			log.error("Error while running progress " + name, e);
		}
	}

	/**
	 * Returns the workspace directory where databases and other resources are
	 * stored (native libraries, HTML resources, etc.).
	 */
	public static File getWorkspace() {
		return Workspace.getDir();
	}
}
