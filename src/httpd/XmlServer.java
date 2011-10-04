
package no.polaric.aprsd.http;
import no.polaric.aprsd.*;

import org.simpleframework.http.core.Container;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.simpleframework.http.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.io.PrintStream;

import uk.me.jstott.jcoord.*;
import java.util.*;
import java.io.*;
import java.text.*;
import com.mindprod.base64.Base64;
import java.util.concurrent.locks.*; 



public class XmlServer extends ServerBase
{
   private String _icon; 
   private String _adminuser, _updateusers;
   private int _seq = 0;
   
   public XmlServer(ServerAPI api, Properties config) throws IOException
   {
      super(api, config);
      _icon = config.getProperty("map.icon.default", "sym.gif").trim();

      
      int trailage= Integer.parseInt(config.getProperty("map.trail.maxAge", "15").trim());
      Trail.setMaxAge(trailage * 60 * 1000); 
      int trailpause= Integer.parseInt(config.getProperty("map.trail.maxPause", "10").trim());
      Trail.setMaxPause(trailpause * 60 * 1000);
      int trailage_ext= Integer.parseInt(config.getProperty("map.trail.maxAge.extended", "30").trim());
      Trail.setMaxAge_Ext(trailage_ext * 60 * 1000); 
      int trailpause_ext= Integer.parseInt(config.getProperty("map.trail.maxPause.extended", "20").trim());
      Trail.setMaxPause_Ext(trailpause_ext * 60 * 1000);
   }


   
   /**
    * Look up a station and return id, x and y coordinates (separated by commas).
    * If not found, return nothing.
    */
   public void handle_findstation(Request req, Response res)
       throws IOException
   { 
       PrintWriter out = getWriter(res);
       String ident = req.getParameter("id").toUpperCase();
       AprsPoint s = _api.getDB().getItem(ident, null);
       if (s==null) {
          int i = ident.lastIndexOf('-');
          if (i > -1)    
             ident = ident.substring(0, i);
          List<AprsPoint> l = _api.getDB().getAll(ident);
          if (l.size() > 0)
              s = l.get(0);
       }
       if (s!=null && !s.expired() && s.getPosition() != null) {
          UTMRef xpos = toUTM(s.getPosition()); 
          out.println(s.getIdent()+","+ (long) Math.round(xpos.getEasting()) + "," + (long) Math.round(xpos.getNorthing()));   
       }
       res.set("Content-Type", "text/csv; charset=utf-8");
       out.close();
   }
   


   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    * Secure version. Assume that this is is called only when user is logged in and
    * authenticated by frontend webserver.
    */
   public void handle_mapdata_sec(Request req, Response res) 
       throws IOException
   {
      boolean showSarInfo = (getAuthUser(req) != null || _api.getSar() == null);
      _handle_mapdata(req, res, showSarInfo);
   }
   
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   public void handle_mapdata(Request req, Response res)
      throws IOException
   {
      _handle_mapdata(req, res, _api.getSar() == null);
   }
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   public void _handle_mapdata(Request req, Response res, boolean showSarInfo) 
      throws IOException
   {         
        PrintWriter out = getWriter(res);
        String filt = _infraonly ? "infra" : req.getParameter("filter");
        ViewFilter vfilt = ViewFilter.getFilter(filt);
        res.set("Content-Type", "text/xml; charset=utf-8");
                
        UTMRef uleft = null, lright = null;
        Form parms = req.getForm();
        if (parms.get("x1") != null) {
          long x1 = Long.parseLong( parms.get("x1") );
          long x2 = Long.parseLong( parms.get("x2") );
          long x3 = Long.parseLong( parms.get("x3") );    
          long x4 = Long.parseLong( parms.get("x4") );
          uleft = new UTMRef((double) x1, (double) x2, _utmlatzone, _utmzone); 
          lright = new UTMRef((double) x3, (double) x4, _utmlatzone, _utmzone);
        }
        long scale = 0;
        if (parms.get("scale") != null)
           scale = Long.parseLong(parms.get("scale"));
        
        /* Sequence number at the time of request */
        int seq  = 0;
        synchronized (this) {
          _seq = (_seq+1) % 32000;
          seq = _seq;
        }
        long client = getSession(req);
        
        /* If requested, wait for a state change (see Notifier.java) */
        if (parms.get("wait") != null) 
            if (! Station.waitChange(uleft, lright, client) ) {
                out.println("<overlay cancel=\"true\"/>");             
                out.close();
                return;
            }
             
        /* XML header with meta information */           
        out.println("<overlay seq=\""+_seq+"\"" +
            (filt==null ? ""  : " view=\"" + filt + "\"") + ">");
            
        printXmlMetaTags(out, req);
        out.println("<meta name=\"clientses\" value=\""+ client + "\"/>");    
        
        
        /* Output signs. A sign is not an APRS object
         * just a small icon and a title. It may be a better idea to do this
         * in map-layers instead?
         */
        int i=0;
        for (Signs.Item s: Signs.getList())
        {
            UTMRef ref = toUTM(s.getPosition()); 
            if (ref == null || !s.visible(scale) || !s.isInside(uleft, lright))
                continue;
            String title = s.getDescr() == null ? "" : "title=\"" + fixText(s.getDescr()) + "\"";
            String icon = _wfiledir +"/icons/"+ s.getIcon();    
           
            out.println("<point id=\"__sign" + (i++) + "\" x=\""
                         + (int) Math.round(ref.getEasting()) + "\" y=\"" + (int) Math.round(ref.getNorthing())+ "\" " 
                         + title+">");
            out.println("   <icon src=\""+icon+"\"  w=\"22\" h=\"22\" ></icon>");     
            out.println("</point>");    
        }        
        
        /* Output APRS objects */
        for (AprsPoint s: _api.getDB().search(uleft, lright)) 
        {
        
            if (s.getPosition() == null)
                continue; 
            if (!vfilt.useObject(s))
                continue;
                   
            UTMRef ref = toUTM(s.getPosition()); 
            if (ref == null) continue; 
               
            if (!s.visible()) 
                   out.println("<delete id=\""+fixText(s.getIdent())+"\"/>");
            else {
               synchronized(s) {
                  ref = toUTM(s.getPosition()); 
                  if (ref == null) continue; 
                  
                  String title = s.getDescr() == null ? "" 
                             : "title=\"[" + fixText(s.getIdent()) + "] " + fixText(s.getDescr()) + "\"";
                  String icon = _wfiledir + "/icons/"+ (s.getIcon() != null ? s.getIcon() : _icon);    
                
                  out.println("<point id=\""+fixText(s.getIdent())+"\" x=\""
                               + (int) Math.round(ref.getEasting()) + "\" y=\"" + (int) Math.round(ref.getNorthing())+ "\" " 
                               + title + (s.isChanging() ? " redraw=\"true\"" : "") +
                               ((s instanceof AprsObject) && _api.getDB().getOwnObjects().hasObject(s.getIdent().replaceFirst("@.*",""))  ? " own=\"true\"":"") +">");
                  out.println("   <icon src=\""+icon+"\"  w=\"22\" h=\"22\" ></icon>");     
        
                  if (vfilt.showIdent(s)) {
                     String style = "lobject";
                     if (s instanceof Station)
                        style = (!(((Station) s).getTrail().isEmpty()) ? "lmoving" : "lstill");
                     if (s.isEmergency())
                        style += " lflash";
                        
                     if (vfilt instanceof ViewFilter.Infra) {
                        if (s instanceof Station && ((Station)s).isIgate())
                           style += " igate";
                        if (s instanceof Station && ((Station)s).isWideDigi())
                           style += " wdigi";
                     }
                                                   
                     out.println("   <label style=\""+style+"\">");
                     out.println("       "+fixText(s.getDisplayId(showSarInfo)));
                     out.println("   </label>"); 
                  }
                  if (s instanceof Station) {
                     Trail h = ((Station)s).getTrail();
                     Station ss = (Station) s;
                     if (!h.isEmpty())
                        printTrailXml(out, ss.getTrailColor(), ss.getPosition(), h, uleft, lright);
                  }
               } /* synchronized(s) */
               
               if (vfilt.showPath(s) && s.isInfra())
                  printPathXml(out, (Station) s, uleft, lright);              
               out.println("</point>");
            }
          
            /* Allow other threads to run */ 
            Thread.currentThread().yield ();
        }        
        out.println("</overlay>");
        out.close();
   }

     

   
}