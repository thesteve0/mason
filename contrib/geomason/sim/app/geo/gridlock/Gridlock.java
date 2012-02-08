/**
 ** Gridlock.java
 **
 ** Copyright 2011 by Andrew Crooks, Sarah Wise, Mark Coletti, and
 ** George Mason University.
 **
 ** Licensed under the Academic Free License version 3.0
 **
 ** See the file "LICENSE" for more information
 **
 **/
package sim.app.geo.gridlock;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.planargraph.Node;

import sim.engine.SimState;
import sim.engine.Steppable;
import sim.field.geo.GeomVectorField;
import sim.io.geo.ShapeFileImporter;
import sim.util.geo.AttributeValue;
import sim.util.geo.GeomPlanarGraph;
import sim.util.geo.GeomPlanarGraphEdge;
import sim.util.geo.MasonGeometry;



/**
 * The  simulation core.
 * 
 * The simulation can require a LOT of memory, so make sure the virtual machine has enough.
 * Do this by adding the following to the command line, or by setting up your run 
 * configuration in Eclipse to include the VM argument:
 * 
 * 		-Xmx2048M
 * 
 * With smaller simulations this chunk of memory is obviously not necessary. You can 
 * take it down to -Xmx800M or some such. If you get an OutOfMemory error, push it up.
 */
//@SuppressWarnings("restriction")
public class Gridlock extends SimState
{
    private static final long serialVersionUID = 1L;

    public GeomVectorField roads = new GeomVectorField();
    public GeomVectorField censusTracts = new GeomVectorField();

    // traversable network
    public GeomPlanarGraph network = new GeomPlanarGraph();

    public GeomVectorField junctions = new GeomVectorField();

    // mapping between unique edge IDs and edge structures themselves
    HashMap<Integer, GeomPlanarGraphEdge> idsToEdges =
        new HashMap<Integer, GeomPlanarGraphEdge>();

    HashMap<GeomPlanarGraphEdge, ArrayList<Agent>> edgeTraffic =
        new HashMap<GeomPlanarGraphEdge, ArrayList<Agent>>();

    public GeomVectorField agents = new GeomVectorField();

    ArrayList<Agent> agentList = new ArrayList<Agent>();
    
    // system parameter: can force agents to go to or from work at any time
    boolean goToWork = true;



    public boolean getGoToWork()
    {
        return goToWork;
    }



    public void setGoToWork(boolean val)
    {
        goToWork = val;
    }

    // cheap, hacky, hard-coded way to identify which edges are associated with
    // goal Nodes. Done because we cannot seem to read in .shp file for goal nodes because
    // of an NegativeArraySize error? Any suggestions very welcome!
    Integer[] goals =
    {
        72142, 72176, 72235, 72178
    };



    /** Constructor */
    public Gridlock(long seed)
    {
        super(seed);
    }



    /** Initialization */
    public void start()
    {
        super.start();

        // read in data
        try
        {
            ShapeFileImporter importer = new ShapeFileImporter();

            // read in the roads to create the transit network
            System.out.println("reading roads layer...");
            importer.ingest("../../data/gridlock/roads.shp", Gridlock.class, roads, null);

            Envelope MBR = roads.getMBR();

            // read in the tracts to create the background
            System.out.println("reading tracts layer...");
            importer.ingest("../../data/gridlock/areas.shp", Gridlock.class, censusTracts, null);


            MBR.expandToInclude(censusTracts.getMBR());

            createNetwork();

            // update so that everyone knows what the standard MBR is
            roads.setMBR(MBR);
            censusTracts.setMBR(MBR);

            // initialize agents
            populate("../../data/gridlock/roads_points_place.csv");
            agents.setMBR(MBR);

            /** Steppable that flips Agent paths once everyone reaches their destinations*/
            Steppable flipper = new Steppable()
            {

                @Override
                public void step(SimState state)
                {

                    Gridlock gstate = (Gridlock) state;

                    // pass to check if anyone has not yet reached work
                    for (Agent a : gstate.agentList)
                    {
                        if (!a.reachedDestination)
                        {
                            return; // someone is still moving: let him do so
                        }
                    }
                    // send everyone back in the opposite direction now
                    boolean toWork = gstate.goToWork;
                    gstate.goToWork = !toWork;

                    // otherwise everyone has reached their latest destination:
                    // turn them back
                    for (Agent a : gstate.agentList)
                    {
                        a.flipPath();
                    }
                }

            };
            schedule.scheduleRepeating(flipper, 10);

        } catch (FileNotFoundException e)
        {
            System.out.println("Error: missing required data file");
        }
    }



    /** Create the road network the agents will traverse
     *
     */
    private void createNetwork()
    {
        System.out.println("creating network...");

        network.createFromGeomField(roads);
        
        for (Object o : network.getEdges())
        {
            GeomPlanarGraphEdge e = (GeomPlanarGraphEdge) o;

            idsToEdges.put(e.getDoubleAttribute("ID_ID").intValue(), e);
            
            // Below deprecated as now MasonGeometry attributes are copied to
            // edges in constructed graph.
            
//            ArrayList attributes = (ArrayList) e.getLine().getUserData();
//            for (Object att : attributes)
//            {
//                AttributeValue a = (AttributeValue) att;
//                if (a.getName().equals("ID_ID"))
//                {
//                    int val = ((Double) a.getValue()).intValue();
//                    idsToEdges.put(val, e);
//                }
//            }

            e.setData(new ArrayList<Agent>());
        }

        addIntersectionNodes(network.nodeIterator(), junctions);
    }



    /**
     * Read in the population file and create an appropriate pop
     * @param filename
     */
    public void populate(String filename)
    {

        try
        {
            String filePath = Gridlock.class.getResource(filename).getPath();

            FileInputStream fstream = new FileInputStream(filePath);

            BufferedReader d = new BufferedReader(new InputStreamReader(fstream));
            String s;

            d.readLine(); // get rid of the header
            while ((s = d.readLine()) != null)
            { // read in all data
                String[] bits = s.split(",");
                int pop = Integer.parseInt(bits[11]); // TODO: reset me if desired!
                String workTract = bits[5];
                String homeTract = bits[8];
                String id_id = bits[13];
                GeomPlanarGraphEdge startingEdge =
                    idsToEdges.get(
                    (int) Double.parseDouble(id_id));
                GeomPlanarGraphEdge goalEdge = idsToEdges.get(
                    goals[ random.nextInt(goals.length)]);
                for (int i = 0; i < 1; i++)
                {//pop; i++){
                    Agent a = new Agent(this, homeTract, workTract, startingEdge, goalEdge);

                    boolean successfulStart = a.start(this);

                    if (!successfulStart)
                    {
                        continue; // DON'T ADD IT if it's bad
                    }

                    MasonGeometry newGeometry = new MasonGeometry(a.getGeometry());
                    newGeometry.isMovable = true;
                    agents.addGeometry(newGeometry);
                    agentList.add(a);
                    schedule.scheduleRepeating(a);
                }
            }

            // clean up
            d.close();

        } catch (Exception e)
        {
            System.out.println("ERROR: issue with population file: " + e);
        }

    }



    /** adds nodes corresponding to road intersections to GeomVectorField
     *
     * @param nodeIterator Points to first node
     * @param intersections GeomVectorField containing intersection geometry
     *
     * Nodes will belong to a planar graph populated from LineString network.
     */
    private void addIntersectionNodes(Iterator<?> nodeIterator,
                                      GeomVectorField intersections)
    {
        GeometryFactory fact = new GeometryFactory();
        Coordinate coord = null;
        Point point = null;
        int counter = 0;

        while (nodeIterator.hasNext())
        {
            Node node = (Node) nodeIterator.next();
            coord = node.getCoordinate();
            point = fact.createPoint(coord);

            junctions.addGeometry(new MasonGeometry(point));
            counter++;
        }
    }



    /** Main function allows simulation to be run in stand-alone, non-GUI mode */
    public static void main(String[] args)
    {
        doLoop(Gridlock.class, args);
        System.exit(0);
    }

}