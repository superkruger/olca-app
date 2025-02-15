package org.openlca.app.tools.mapping.replacer;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.openlca.app.db.Database;
import org.openlca.app.tools.mapping.model.DBProvider;
import org.openlca.app.util.Labels;
import org.openlca.core.database.FlowDao;
import org.openlca.core.database.IDatabase;
import org.openlca.core.matrix.cache.ConversionTable;
import org.openlca.core.model.Flow;
import org.openlca.io.maps.FlowMapEntry;
import org.openlca.io.maps.FlowRef;
import org.openlca.io.maps.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Replacer implements Runnable {

	private final ReplacerConfig conf;
	private final Logger log = LoggerFactory.getLogger(getClass());

	final IDatabase db;
	// the valid entries that could be applied: source flow ID -> mapping.
	final HashMap<Long, FlowMapEntry> entries = new HashMap<>();
	// the source and target flows in the database: flow ID -> flow.
	final HashMap<Long, Flow> flows = new HashMap<>();
	ConversionTable conversions;

	public Replacer(ReplacerConfig conf) {
		this.conf = conf;
		this.db = Database.get();
	}

	@Override
	public void run() {
		if (conf == null || (!conf.processes && !conf.methods)) {
			log.info("no configuration; nothing to replace");
			return;
		}

		buildIndices();
		if (entries.isEmpty()) {
			log.info("found no flows that can be mapped");
			return;
		}
		log.info("found {} flows that can be mapped", entries.size());

		try {

			log.info("start updatable cursors");
			Cursor exchangeCursor = null;
			Cursor impactCursor = null;
			ExecutorService pool = Executors.newFixedThreadPool(4);
			if (conf.processes) {
				exchangeCursor = new Cursor(Cursor.EXCHANGES, this);
				pool.execute(exchangeCursor);
			}
			if (conf.methods) {
				impactCursor = new Cursor(Cursor.IMPACTS, this);
				pool.execute(impactCursor);
			}

			// waiting for the cursors to finish
			pool.shutdown();
			int i = 0;
			while (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
				i++;
				log.info("waiting for cursors to finish; {} seconds", i * 10);
			}
			log.info("cursors finished");

			// TODO when products were replaced we also need to check
			// whether these products are used in the quant. ref. of
			// product systems and project variants and convert the
			// amounts there.
			// TODO also: we need to replace wuch flows in allocation
			// factors; the application of the conversion factor is
			// not required there.

			// collect and log statistics
			Stats stats = new Stats();
			if (exchangeCursor != null) {
				stats.add(exchangeCursor.stats);
				exchangeCursor.stats.log("exchanges", flows);
			}
			if (impactCursor != null) {
				stats.add(impactCursor.stats);
				impactCursor.stats.log("impacts", flows);
			}

			boolean deleteMapped = false;
			Set<Long> usedFlows = null;
			if (conf.deleteMapped && conf.processes && conf.methods) {
				if (stats.failures > 0) {
					log.warn("Will not delete mapped flows because"
							+ " there were {} failures in replacement process",
							stats.failures);
				} else {
					deleteMapped = true;
					usedFlows = new FlowDao(db).getUsed();
				}
			}

			// update the mapping entries
			for (Long flowID : entries.keySet()) {
				FlowMapEntry e = entries.get(flowID);
				if (flowID == null || e == null)
					continue;
				if (stats.hadFailures(flowID)) {
					e.sourceFlow.status = Status.error("Replacement error");
					continue;
				}
				if (deleteMapped && !usedFlows.contains(flowID)) {
					FlowDao dao = new FlowDao(db);
					Flow flow = dao.getForId(flowID);
					dao.delete(flow);
					log.info("removed mapped flow {} uuid={}",
							Labels.getDisplayName(flow), flow.refId);
					e.sourceFlow.status = Status.ok("Applied and removed");
				} else {
					e.sourceFlow.status = Status.ok("Applied (not removed)");
				}
			}

		} catch (Exception e) {
			log.error("Flow replacement failed", e);
		}
	}

	private void buildIndices() {

		// first persist all target flows in the database that
		// do not have an error flag
		List<FlowRef> targetFlows = conf.mapping.entries.stream()
				.filter(e -> e.targetFlow != null
						&& e.targetFlow.status != null
						&& !e.targetFlow.status.isError())
				.map(e -> e.targetFlow)
				.collect(Collectors.toList());

		conf.provider.persist(targetFlows, db);

		DBProvider dbProvider = new DBProvider(db);
		for (FlowMapEntry e : conf.mapping.entries) {

			// only do the replacement for matched mapping entries
			// (both flows should have no error flag)
			if (e.sourceFlow == null
					|| e.sourceFlow.status == null
					|| e.sourceFlow.status.isError()
					|| e.targetFlow == null
					|| e.targetFlow.status == null
					|| e.targetFlow.status.isError())
				continue;

			// sync the flows
			Flow source = dbProvider.sync(e.sourceFlow);
			if (source == null)
				continue;
			Flow target = dbProvider.sync(e.targetFlow);
			if (target == null)
				continue;

			entries.put(source.id, e);
			flows.put(source.id, source);
			flows.put(target.id, target);
		}
		conversions = ConversionTable.create(db);
	}
}
