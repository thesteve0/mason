package sim.field;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpi.Datatype;
import mpi.MPI;
import mpi.MPIException;
import sim.engine.DSimState;
import sim.engine.IterativeRepeat;
import sim.engine.Steppable;
import sim.engine.Stopping;
import sim.engine.transport.AgentWrapper;
import sim.engine.transport.PayloadWrapper;
import sim.engine.transport.TransportRMIInterface;
import sim.engine.transport.RMIProxy;
import sim.field.partitioning.PartitionInterface;
import sim.field.partitioning.QuadTreePartition;
import sim.field.partitioning.IntHyperRect;
import sim.field.partitioning.IntPoint;
import sim.field.partitioning.IntPointGenerator;
import sim.field.partitioning.NdPoint;
import sim.field.storage.GridStorage;
import sim.util.GroupComm;
import sim.util.MPIParam;
import sim.util.MPIUtil;

import java.rmi.Remote;

/**
 * All fields in distributed MASON must extend this class.
 *
 * @param <T> The Class of Object to store in the field
 * @param <P> The Type of P to use
 * @param <S> The Type of Storage to use
 */
public class HaloGrid2D<T extends Serializable , P extends NdPoint, S extends GridStorage> implements TransportRMIInterface<Serializable, P>, Synchronizable{

	// Helper class to organize neighbor-related data structures and methods
	class Neighbor {
		int pid;
		MPIParam sendParam, recvParam;

		public Neighbor(final IntHyperRect neighborPart) {
			pid = neighborPart.getId();
			final ArrayList<IntHyperRect> sendOverlaps = generateOverlaps(origPart, neighborPart.resize(aoi));
			final ArrayList<IntHyperRect> recvOverlaps = generateOverlaps(haloPart, neighborPart);

			assert sendOverlaps.size() == recvOverlaps.size();

			// Sort these overlaps so that they corresponds to each other
			Collections.sort(sendOverlaps);
			Collections.sort(recvOverlaps, Collections.reverseOrder());

			sendParam = new MPIParam(sendOverlaps, haloPart, MPIBaseType);
			recvParam = new MPIParam(recvOverlaps, haloPart, MPIBaseType);
		}

		private ArrayList<IntHyperRect> generateOverlaps(final IntHyperRect p1, final IntHyperRect p2) {
			final ArrayList<IntHyperRect> overlaps = new ArrayList<IntHyperRect>();

			if (partition.isToroidal())
				for (final IntPoint p : IntPointGenerator.getLayer(numDimensions, 1)) {
					final IntHyperRect sp = p2
							.shift(IntStream.range(0, numDimensions).map(i -> p.c[i] * fieldSize[i]).toArray());
					if (p1.isIntersect(sp))
						overlaps.add(p1.getIntersection(sp));
				}
			else
				overlaps.add(p1.getIntersection(p2));

			return overlaps;
		}
	}


	protected int numDimensions, numNeighbors;
	protected int[] aoi, fieldSize, haloSize;

	public IntHyperRect world, haloPart, origPart, privatePart;

	protected List<Neighbor> neighbors; // pointer to the processors who's partitions neighbor me
	public S localStorage;
	public PartitionInterface partition;
	public Datatype MPIBaseType;

	public final int fieldIndex;

	public RMIProxy<T, P> proxy;
	protected final DSimState state;

	public HaloGrid2D(final PartitionInterface ps, final int[] aoi, final S stor, final DSimState state) {
		this.partition = ps;
		this.aoi = aoi;
		localStorage = stor;
		this.state = state;
		// init variables that don't change with the partition scheme
		numDimensions = ps.getNumDim();
		world = ps.getField();
		fieldSize = ps.getFieldSize();
		MPIBaseType = localStorage.getMPIBaseType();
		registerCallbacks();
		// init variables that may change with the partition scheme
		reload();
		fieldIndex = state.registerField(this);
	}

	private void registerCallbacks() {

		final List<GridStorage> tempStor = new ArrayList<GridStorage>();
		final QuadTreePartition q = (QuadTreePartition) partition;
		partition.registerPreCommit(arg -> {
//			final int level = (int) arg;
//			GridStorage s = null;
//
//			if (q.isGroupMaster(level))
//				s = localStorage.getNewStorage(q.getNodeShapeAtLevel(level));
//
//			try { collectGroup(level, s); } catch (final Exception e) {
//				e.printStackTrace();
//				System.exit(-1);
//			}
//			if (q.isGroupMaster(level)) tempStor.add(s);
		});
		partition.registerPostCommit(arg -> {
//			final int level = (int) arg;
//			GridStorage s = null;

			reload();

//			if (q.isGroupMaster(level))
//				s = tempStor.remove(0);
//
//			try {
//				distributeGroup(level, s);
//			} catch (final Exception e) {
//				e.printStackTrace();
//				System.exit(-1);
//			}
		});

	}

	public void reload() {
		origPart = partition.getPartition();
		// Get the partition representing halo and local area by expanding the original
		// partition by aoi at each dimension
		haloPart = origPart.resize(aoi);
		haloSize = haloPart.getSize();
		localStorage.reshape(haloPart);
		// Get the partition representing private area by shrinking the original
		// partition by aoi at each dimension
		privatePart = origPart.resize(Arrays.stream(aoi).map(x -> -x).toArray());
		// Get the neighbors and create Neighbor objects
		neighbors = Arrays.stream(partition.getNeighborIds()).mapToObj(x -> new Neighbor(partition.getPartition(x)))
				.collect(Collectors.toList());
		numNeighbors = neighbors.size();
	}

	public IntPoint toLocalPoint(final IntPoint p) {
		return p.rshift(haloPart.ul().getArray());
	}

	public IntPoint toToroidal(final IntPoint p) {
		return p.toToroidal(world);
	}


	public void add(final P p, final T t) {
		if (!inLocal(p)) {
			addToRemote(p, t);
		}
		else {
			localStorage.setLocation(t, p);
			}
	}

	public void remove(final P p, final T t) {
		if (!inLocal(p))
			removeFromRemote(p, t);
		else
			localStorage.removeObject(t);
	}

	public void remove(final P p) {
		if (!inLocal(p))
			removeFromRemote(p);
		else
			localStorage.removeObjects(p);
	}

	public void move(final P fromP, final P toP, final T t) {
		final int fromPid = partition.toPartitionId(fromP);
		final int toPid = partition.toPartitionId(toP);

		if (fromPid == toPid && fromPid != partition.pid) {
			// So that we make only a single RMI call instead of two
			try {
				proxy.getField(partition.toPartitionId(fromP)).moveRMI(fromP, toP, t);

			} catch (final RemoteException e) {
				throw new RuntimeException(e);
			}
		} else {
			remove(fromP, t);
			add(toP, t);
		}
	}
	
//	
//	// TODO: for abitrary object
//		public void globalAgentsInitialization(HashMap<P, ArrayList<T>> agents) {
//
//			HashMap<P,ArrayList<T>>[] sendObjs = null;
//			
//			if(partition.getPid() == 0) {
//				sendObjs = new HashMap[partition.numProcessors];
//				for (P pos: agents.keySet()) {
//					if (sendObjs[partition.toPartitionId(pos)] == null) {
//						sendObjs[partition.toPartitionId(pos)] = new HashMap<P, ArrayList<T>>();
//					}
//					if(sendObjs[partition.toPartitionId(pos)].containsKey(pos)) {
//						sendObjs[partition.toPartitionId(pos)].get(pos).addAll(agents.get(pos));
//					}else {
//						sendObjs[partition.toPartitionId(pos)].put(pos, new ArrayList<>(agents.get(pos)));
//					}
//				}
//			}
//			//System.out.println("sendbuf "+sendObjs);
//			//System.out.println("a moment before Scatter");
//			HashMap<P, ArrayList<T>> recvObject = null;
//			try {
//				recvObject = MPIUtil.scatter(MPI.COMM_WORLD, sendObjs, 0);
//			}catch (Exception e) {
//				// TODO: handle exception
//			}
//			//System.out.println("a moment after Scatter");
//			for(P pos : recvObject.keySet()) {
//				for (T t: recvObject.get(pos)) {
//					if (t instanceof Stopping)
//						addAgent(pos, t);
//					else
//						add(pos, t);
//				}
//			}
//		}
//		
//		// TODO: for abitrary object
//		public void globalRepeatingAgentsInitialization(HashMap<P, ArrayList<T>> agents) {
//
//			HashMap<P,ArrayList<T>>[] sendObjs = null;
//			
//			if(partition.getPid() == 0) {
//				sendObjs = new HashMap[partition.numProcessors];
//				for (P pos: agents.keySet()) {
//					if (sendObjs[partition.toPartitionId(pos)] == null) {
//						sendObjs[partition.toPartitionId(pos)] = new HashMap<P, ArrayList<T>>();
//					}
//					if(sendObjs[partition.toPartitionId(pos)].containsKey(pos)) {
//						sendObjs[partition.toPartitionId(pos)].get(pos).addAll(agents.get(pos));
//					}else {
//						sendObjs[partition.toPartitionId(pos)].put(pos, new ArrayList<T>(agents.get(pos)));
//					}
//				}
//			}
//			//System.out.println("sendbuf "+sendObjs);
//			//System.out.println("a moment before Scatter");
//			HashMap<P, ArrayList<T>> recvObject = null;
//			try {
//				recvObject = MPIUtil.scatter(MPI.COMM_WORLD, sendObjs, 0);
//			}catch (Exception e) {
//				// TODO: handle exception
//			}
//			//System.out.println("a moment after Scatter");
//			for(P pos : recvObject.keySet()) {
//				for (T t: recvObject.get(pos)) {
//					if (t instanceof Stopping)
//						addRepeatingAgent(pos, t, 1, 1);
//					else 
//						add(pos,t);
//				}
//			}
//		}

	public void addAgent(final P p, final T t) {
		// TODO: is there a better way than just doing a Type Cast?
		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		final Stopping agent = (Stopping) t;

		if (inLocal(p)) {
			add(p, t);
			state.schedule.scheduleOnce(agent);
		} else
			state.getTransporter().migrateAgent(agent, partition.toPartitionId(p));
	}

	public void addAgent(final P p, final T t, final int ordering, final double time) {
		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		final Stopping agent = (Stopping) t;

		if (inLocal(p)) {
			add(p, t);
			state.schedule.scheduleOnce(time, ordering, agent);
		} else
			state.getTransporter().migrateAgent(ordering, time, agent, partition.toPartitionId(p));
	}

	public void moveAgent(final P fromP, final P toP, final T t) {
		
		if (!inLocal(fromP)) {
			System.out.println("pid "+partition.pid +" agent"+t);
			System.out.println("partitioning "+partition.getPartition());
			throw new IllegalArgumentException("fromP must be local");
			}

		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		final Stopping agent = (Stopping) t;

		remove(fromP, t);

		if (inLocal(toP)) {
			add(toP, t);
			state.schedule.scheduleOnce(agent);
		} else
			state.getTransporter().migrateAgent(agent,
					partition.toPartitionId(toP), toP, fieldIndex);
	}

	public void moveAgent(final P fromP, final P toP, final T t, final int ordering, final double time) {
		if (!inLocal(fromP))
			throw new IllegalArgumentException("fromP must be local");

		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		final Stopping agent = (Stopping) t;

		remove(fromP, t);

		if (inLocal(toP)) {
			add(toP, t);
			state.schedule.scheduleOnce(time, ordering, agent);
		} else
			state.getTransporter().migrateAgent(ordering, time, agent,
					partition.toPartitionId(toP), toP, fieldIndex);
	}

	public void addRepeatingAgent(final P p, final T t, final double time, final int ordering, final double interval) {
		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		if (inLocal(p)) {
			add(p, t);
			final IterativeRepeat iterativeRepeat = state.schedule.scheduleRepeating(time, ordering,
					(Stopping) t, interval);
		} else {
			final IterativeRepeat iterativeRepeat = new IterativeRepeat((Stopping) t, time, interval, ordering);
			state.getTransporter().migrateRepeatingAgent(iterativeRepeat, partition.toPartitionId(p));
		}
	}

	public void addRepeatingAgent(final P p, final T t, final int ordering, final double interval) {
		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		if (inLocal(p)) {
			add(p, t);
			final IterativeRepeat iterativeRepeat = state.schedule.scheduleRepeating((Stopping) t,
					ordering, interval);
		} else {
			// TODO: look at the time here
			final IterativeRepeat iterativeRepeat = new IterativeRepeat((Stopping) t, -1, interval, ordering);
			state.getTransporter().migrateRepeatingAgent(iterativeRepeat, partition.toPartitionId(p));
		}
	}

	public void removeAndStopRepeatingAgent(final P p, final T t) {
		if (!inLocal(p))
			throw new IllegalArgumentException("p must be local");

		// TODO: Should we remove the instanceOf check and assume that the
		// pre-conditions are always met?
		if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		final Stopping stopping = (Stopping) t;
		remove(p, t);
		stopping.getStoppable().stop();
	}

	public void removeAndStopRepeatingAgent(final P p, final IterativeRepeat iterativeRepeat) {
		if (!inLocal(p))
			throw new IllegalArgumentException("p must be local");

		final Steppable step = iterativeRepeat.getSteppable();

		if (!(step instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");

		final Stopping stopping = (Stopping) step;
		remove(p, (T) step);
		stopping.getStoppable().stop();
	}

	public void moveRepeatingAgent(final P fromP, final P toP, final T t) {
		if (!inLocal(fromP))
			throw new IllegalArgumentException("fromP must be local");

		remove(fromP, t);

		// TODO: Should we remove the instanceOf check and assume that the
		// pre-conditions are always met?
		if (inLocal(toP))
			add(toP, t);
		else if (!(t instanceof Stopping))
			throw new IllegalArgumentException("t must be a Stopping");
		else {
			final Stopping stopping = (Stopping) t;
			final IterativeRepeat iterativeRepeat = (IterativeRepeat) stopping.getStoppable();
			state.getTransporter().migrateRepeatingAgent(iterativeRepeat, partition.toPartitionId(toP), toP,
					fieldIndex);
			iterativeRepeat.stop();
		}
	}

	public void moveRepeatingAgent(final P fromP, final P toP, final IterativeRepeat iterativeRepeat) {
		if (!inLocal(fromP))
			throw new IllegalArgumentException("fromP must be local");

		// We cannot use checked cast for generics because of erasure
		// TODO: is there a safe way of doing this?

		final T t = (T) iterativeRepeat.getSteppable();

		remove(fromP, t);

		if (inLocal(toP))
			add(toP, t);
		else {
			state.getTransporter().migrateRepeatingAgent(iterativeRepeat, partition.toPartitionId(toP), toP,
					fieldIndex);
			iterativeRepeat.stop();
		}
	}

	public void moveLocal(final P fromP, final P toP, final T t) {
		//removeLocal(fromP, t);
		localStorage.removeObject(t);
		localStorage.setLocation(t, toP);
		//addLocal(toP, t);
	}

	// TODO make a copy of the storage which will be used by the remote field access

	// TODO: Do we need to check for type safety here?
	// If the getField method returns the current field then
	// this cast should work

	public Serializable getFromRemote(final P p) {
		try {
			//System.out.println(p);
			return proxy.getField(partition.toPartitionId(p)).getRMI(p);
		} catch (final NullPointerException e) {
			throw new IllegalArgumentException("Remote Proxy is not initialized");
		} catch (final RemoteException e) {
			throw new RuntimeException(e);
		}
	}

	public void addToRemote(final P p, final T t) {
		try {
			proxy.getField(partition.toPartitionId(p)).addRMI(p, t);
		} catch (final NullPointerException e) {
			throw new IllegalArgumentException("Remote Proxy is not initialized");
		} catch (final RemoteException e) {
			throw new RuntimeException(e);
		}
	}

	public void removeFromRemote(final P p, final T t) {
		try {
			proxy.getField(partition.toPartitionId(p)).removeRMI(p, t);
		} catch (final NullPointerException e) {
			throw new IllegalArgumentException("Remote Proxy is not initialized");
		} catch (final RemoteException e) {
			throw new RuntimeException(e);
		}
	}

	public void removeFromRemote(final P p) {
		try {
			proxy.getField(partition.toPartitionId(p)).removeRMI(p);
		} catch (final NullPointerException e) {
			throw new IllegalArgumentException("Remote Proxy is not initialized");
		} catch (final RemoteException e) {
			throw new RuntimeException(e);
		}
	}



	public GridStorage getStorage() {
		return localStorage;
	}

	// Various stabbing queries
	public boolean inGlobal(final IntPoint p) {
		return IntStream.range(0, numDimensions).allMatch(i -> p.c[i] >= 0
				&& p.c[i] < fieldSize[i]);
	}

	public boolean inLocal(final P p) {
		return origPart.contains(p);
	}

	public boolean inPrivate(final P p) {
		return privatePart.contains(p);
	}

	public boolean inLocalAndHalo(final P p) {
		return haloPart.contains(p);
	}

	public boolean inShared(final P p) {
		return inLocal(p) && !inPrivate(p);
	}

	public boolean inHalo(final P p) {
		return inLocalAndHalo(p) && !inLocal(p);
	}




	public void collect(final int dst, final GridStorage fullField) throws MPIException {
		final Serializable sendObj = localStorage.pack(new MPIParam(origPart, haloPart, MPIBaseType));

		final ArrayList<Serializable> recvObjs = MPIUtil.<Serializable>gather(partition, sendObj, dst);

		if (partition.getPid() == dst)
			for (int i = 0; i < partition.getNumProc(); i++)
				fullField.unpack(new MPIParam(partition.getPartition(i), world, MPIBaseType), recvObjs.get(i));
	}


	public void collectGroup(final int level, final GridStorage groupField) throws MPIException {
		if (!(partition instanceof QuadTreePartition))
			throw new UnsupportedOperationException(
					"Can only collect from group with DQuadTreePartition, got " + partition.getClass().getSimpleName());

		final QuadTreePartition qt = (QuadTreePartition) partition;
		final GroupComm gc = qt.getGroupComm(level);

		if (gc != null) {
			final Serializable sendObj = localStorage.pack(new MPIParam(origPart, haloPart, MPIBaseType));

			final ArrayList<Serializable> recvObjs = MPIUtil.<Serializable>gather(gc.comm, sendObj, gc.groupRoot);

			if (qt.isGroupMaster(gc))
				for (int i = 0; i < recvObjs.size(); i++)
					groupField.unpack(new MPIParam(gc.leaves.get(i).getShape(), gc.master.getShape(), MPIBaseType),
							recvObjs.get(i));
		}

		MPI.COMM_WORLD.barrier();
	}

	public void distributeGroup(final int level, final GridStorage groupField) throws MPIException {
		if (!(partition instanceof QuadTreePartition))
			throw new UnsupportedOperationException(
					"Can only distribute to group with DQuadTreePartition, got "
							+ partition.getClass().getSimpleName());

		final QuadTreePartition qt = (QuadTreePartition) partition;
		final GroupComm gc = qt.getGroupComm(level);
		Serializable[] sendObjs = null;

		if (gc != null) {
			if (qt.isGroupMaster(gc)) {
				sendObjs = new Serializable[gc.leaves.size()];
				for (int i = 0; i < gc.leaves.size(); i++)
					sendObjs[i] = groupField
					.pack(new MPIParam(gc.leaves.get(i).getShape(), gc.master.getShape(), MPIBaseType));
			}

			final Serializable recvObj = MPIUtil.<Serializable>scatter(gc.comm, sendObjs, gc.groupRoot);

			localStorage.unpack(new MPIParam(origPart, haloPart, MPIBaseType), recvObj);
		}

		syncHalo();
	}

	public String toString() {
		return String.format("PID %d Storage %s", partition.getPid(), localStorage);
	}



	/*METHODS SYNCHO*/

	@Override
	public void initRemote() {
		proxy = new RMIProxy(partition, this);
	}


	@Override
	public void syncHalo() throws MPIException {
		final Serializable[] sendObjs = new Serializable[numNeighbors];
		for (int i = 0; i < numNeighbors; i++)
			sendObjs[i] = localStorage.pack(neighbors.get(i).sendParam);

		final ArrayList<Serializable> recvObjs = MPIUtil.<Serializable>neighborAllToAll(partition, sendObjs);

		for (int i = 0; i < numNeighbors; i++)
			localStorage.unpack(neighbors.get(i).recvParam, recvObjs.get(i));
	}

	@Override
	public void syncObject(PayloadWrapper payloadWrapper) {
		// TODO Auto-generated method stub
		if (payloadWrapper.payload instanceof IterativeRepeat) {
			final IterativeRepeat iterativeRepeat = (IterativeRepeat) payloadWrapper.payload;

			add((P)payloadWrapper.loc, (T)iterativeRepeat.getSteppable());

		} else if (payloadWrapper.payload instanceof AgentWrapper) {

			final AgentWrapper agentWrapper = (AgentWrapper) payloadWrapper.payload;

			add((P)payloadWrapper.loc, (T)agentWrapper.agent);

		} else {
			add((P)payloadWrapper.loc, (T)payloadWrapper.payload);
		}

	}


	/*RMI METHODS*/
	// TODO: Should we throw exception here if not in local?
	public void addRMI( P p,  Serializable t) throws RemoteException {
		//addLocal(p, t);
		localStorage.setLocation(t, p);

	}
	public void removeRMI( P p,  Serializable t) throws RemoteException {
		//removeLocal(p, t);
		localStorage.removeObject(t);
	}
	public void removeRMI(final P p) throws RemoteException {
		//	removeLocal(p);
		localStorage.removeObjects(p);
	}
	public Serializable getRMI(P p) throws RemoteException {

		return localStorage.getObjects(p);
	}








}
