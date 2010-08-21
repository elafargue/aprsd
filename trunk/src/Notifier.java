/* 
 * Copyright (C) 2009 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
 
package no.polaric.aprsd;
import uk.me.jstott.jcoord.*; 
import java.util.*;
import java.io.Serializable;
  
  
  
public class Notifier
{
    private Date signalled = new Date(); 
    private AprsPoint signalledPt; 
    private static long _mintime  = 1000 * 10;   /* Minimum wait time: 10s */
    private static long _timeout  = 1000 * 120;  /* Maximum wait time: 2min */
 
    
    public void waitSignal(UTMRef uleft, UTMRef lright)
    {
         long wstart = (new Date()).getTime();
         long elapsed = 0;
         boolean found = false;
         do {
              try {
                  synchronized(this) {
                    wait(found ? _mintime-elapsed : _timeout-elapsed);
                  }
                  /* Wait a little to allow more updates to arrive */
                  Thread.sleep(500); 
              }
               catch (Exception e) {}    
               elapsed = (new Date()).getTime() - wstart;
            
              /* Has there been events inside the interest zone */
              synchronized(this) {
                 found = found || signalledPt == null || uleft == null || 
                                  signalledPt.isInside(uleft, lright); 
              }
           /* Wait no shorter than _mintime and no longer 
            * than _timeout 
            */
         } while ( !(found && elapsed > _mintime) &&
                   elapsed < _timeout );

    }
    
    
    public synchronized void signal(AprsPoint st)
    {   
         signalled = new Date(); 
         signalledPt = st;
         notifyAll();
    }
}
