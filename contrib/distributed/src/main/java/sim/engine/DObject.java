/*
  Copyright 2020 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/

package sim.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A superclass for objects that may be accessed and queried remotely. To do
 * this, such objects need a unique system-wide ID. This ID is generated through
 * a combination of the PID on which the object was first created, and a counter
 * special to that PID. DObject implements a basic form of equals and hashCode
 * based on comparing these IDs.
 */

public abstract class DObject implements java.io.Serializable
    {
    private static final long serialVersionUID = 1;

    private static int idCounter = 0;
    private static final AtomicInteger threadSafeCounter = new AtomicInteger();

    static int nextCounter()
        {
        if (DSimState.isMultiThreaded())
            return threadSafeCounter.getAndIncrement();
        else
            return idCounter++;
        }

    int firstPID; // this is the PID of the *original* processor on which this object was created
    int localID; // this is a unique ID special to processor PID

    /** Returns a unique system-wideID to this object. */
    public long getID()
        {
        return (((long) firstPID) << 32) | localID;
        }

    /** Sets the ID of the object -- do not call this method
    	unless you are implementing readExternal and need to set up the ID.  
    	Be careful. */
 	void setID(long ID)
        {
        this.firstPID = (int)(ID >>> 32);
        this.localID = (int)(ID & 0x0000FFFFL);
        }

	public void readExternal(java.io.ObjectInput in) throws java.io.IOException
		{
		setID(in.readLong());
		}
		
	public void writeExternal(java.io.ObjectOutput out) throws java.io.IOException
		{
		out.writeLong(getID());
		}

    public DObject()
        {
        firstPID = DSimState.getPID(); // called originally to get the FIRST PID
        localID = nextCounter();
        }

    public boolean equals(Object other)
        {
        if (other == null)
            return false;
        else if (other == this)
            return true;
        else if (!(other instanceof DObject))
            return false;
        else
            return (firstPID == ((DObject) other).firstPID && localID == ((DObject) other).localID);
        }

    public final int hashCode()
        {
        // stolen from Int2D.hashCode()
        int y = this.localID;
        y += ~(y << 15);
        y ^= (y >>> 10);
        y += (y << 3);
        y ^= (y >>> 6);
        y += ~(y << 11);
        y ^= (y >>> 16);
        return firstPID ^ y;
        }

    /**
     * Returns a string consisting of the original pid, followed by the unique local
     * id of the object. Together these form a unique, permanent systemwide id.
     */
    public final String toIDString()
        {
        return firstPID + "/" + localID;
        }

    /** Returns a string consisting of the form CLASSNAME:UNIQUEID@CURRENTPID */
    public String toString()
        {
        int pid = -1;

        try
            {
            pid = DSimState.getPID();
            }
        catch (Throwable e)
            {
            // if its running on the remote visualizer its not going have a pid
            }

        return this.getClass().getName() + ":" + toIDString() + "@" + pid;
        }
        
    /** Called when an object has been migrated to a new partition.
        Override this as you see fit.  The default version does nothing.  */
    public void migrated(DSimState state)  { }
        
    }
