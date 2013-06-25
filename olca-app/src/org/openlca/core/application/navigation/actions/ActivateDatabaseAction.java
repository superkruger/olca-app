package org.openlca.core.application.navigation.actions;

import java.util.List;

import org.eclipse.jface.action.Action;
import org.openlca.core.application.App;
import org.openlca.core.application.db.Database;
import org.openlca.core.application.db.IDatabaseConfiguration;
import org.openlca.core.application.navigation.DatabaseElement;
import org.openlca.core.application.navigation.INavigationElement;
import org.openlca.core.application.navigation.Navigator;
import org.openlca.core.resources.ImageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActivateDatabaseAction extends Action implements INavigationAction {

	private Logger log = LoggerFactory.getLogger(getClass());
	private IDatabaseConfiguration config;

	public ActivateDatabaseAction() {
		setText("Activate");
		setImageDescriptor(ImageType.CONNECT_ICON.getDescriptor());
	}

	@Override
	public boolean accept(INavigationElement element) {
		if (!(element instanceof DatabaseElement))
			return false;
		DatabaseElement e = (DatabaseElement) element;
		if (!(e.getData() instanceof IDatabaseConfiguration))
			return false;
		IDatabaseConfiguration config = (IDatabaseConfiguration) e.getData();
		if (Database.isActive(config))
			return false;
		this.config = config;
		return true;
	}

	@Override
	public boolean accept(List<INavigationElement> elements) {
		return false;
	}

	@Override
	public void run() {
		App.run("Activate database", new Runnable() {
			public void run() {
				activate();
			}
		}, new Runnable() {
			public void run() {
				Navigator.refresh(2);
			}
		});
	}

	private void activate() {
		try {
			Database.close();
			Database.activate(config);
		} catch (Exception e) {
			log.error("Failed to activate database", e);
		}
	}

}