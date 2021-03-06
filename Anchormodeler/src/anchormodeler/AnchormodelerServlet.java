package anchormodeler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.servlet.ServletException;
import javax.servlet.http.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.appengine.api.utils.SystemProperty;

@SuppressWarnings("serial")
public class AnchormodelerServlet extends HttpServlet {

	private static final Logger log = Logger.getLogger(AnchormodelerServlet.class.getName());
	  
	public void doGet(HttpServletRequest req, HttpServletResponse resp)	throws IOException {
		String url = req.getParameter("redirect");
		if ((url != null) && (url.length() > 0)) {
			// Redirect to specified page
			resp.sendRedirect(url);
		} else {
			resp.setContentType("text/plain");
			resp.getWriter().println("Anchormodeler server");
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)	throws ServletException, IOException {
		// resp.setContentType("text/html");

		// This is needed in combination with doOptions to allow
		// cross-site-scripting
		// http://hacks.mozilla.org/2009/07/cross-site-xmlhttprequest-with-cors/
		resp.setHeader("Access-Control-Allow-Credentials", "true");
		String s = req.getHeader("Origin");
		if (s != null)
			resp.setHeader("Access-Control-Allow-Origin", s);
		else
			resp.setHeader("Access-Control-Allow-Origin", "*");

		ServletFileUpload upload = new ServletFileUpload();
		AnchorRequest areq = new AnchorRequest();
		try {
			FileItemIterator iterator = upload.getItemIterator(req);
			while (iterator.hasNext()) {
				FileItemStream item = iterator.next();
				if (item.isFormField()) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(item.openStream()));
					String value = reader.readLine();
					areq.stringParams.put(item.getFieldName(), value);
				} else {
					areq.fileParams.put(item.getFieldName(), item);
				}
			}

			String url = req.getHeader("Referer");
			if (url == null)
				url = "null";
			UserService userService = UserServiceFactory.getUserService();
			if (req.getUserPrincipal() != null) {
				areq.user = userService.getCurrentUser();
				areq.logoutUrl = userService.createLogoutURL(url);
			} else {
				// if a google account is required then return LOGIN:loginurl
				// createLoginUrl requires that the continue-url is on the same
				// server so we redirect to the referrer page instead as a
				// workaround
				// TODO: avoid using hardcoded serveraddress here
				if(isDevelopment())
					url = "http://localhost:8888/anchormodeler";
				else
					url = "http://anchormodeler.appspot.com/anchormodeler?redirect=" + url;
				areq.requireLoginMessage = "LOGIN: " + userService.createLoginURL(url); // req.getRequestURI());
			}

			String action = areq.stringParams.get("action");
			action = (action == null) ? "" : action;
			action = action.toLowerCase();

			if (action.equals("save")) {
				actionSave(areq, resp);
			} else if (action.equals("load")) {
				actionLoad(areq, resp);
			} else if (action.equals("list")) {
				actionList(areq, resp);
			} else if (action.equals("status")) {
				actionStatus(areq, resp);
			} else if (action.equals("delete")) {
				actionDelete(areq, resp);
			} else {
				resp.getWriter().println("ERROR: unknown action");
			}

		} catch (Exception e) {
			resp.getWriter().println("ERROR: " + e.toString());
			log.log(Level.WARNING, e.toString(), e);
		}
	}

	private boolean isDevelopment() {
		return SystemProperty.environment.value()==SystemProperty.Environment.Value.Development;
	}
	
	@Override
	protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// http://code.google.com/p/googleappengine/issues/detail?id=2994
		// CORS requires that browsers do an OPTIONS request before allowing
		// cross-site POSTs. UMP does not require this, but no browser
		// implements
		// UMP at this time. So, we reply to the OPTIONS request to trick
		// browsers into effectively implementing UMP.
		// super.doOptions(req, resp);
		String s;

		s = req.getHeader("Origin");
		if ((s != null) && (!s.equals("null")))
			resp.setHeader("Access-Control-Allow-Origin", s);
		else
			resp.setHeader("Access-Control-Allow-Origin", "*");

		resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");

		s = req.getHeader("Access-Control-Request-Headers");
		if (s != null)
			resp.setHeader("Access-Control-Allow-Headers", s);
		else
			resp.setHeader("Access-Control-Allow-Headers", "Content-Type");

		resp.setHeader("Access-Control-Allow-Credentials", "true");
		resp.setHeader("Access-Control-Max-Age", "86400");
	}

	private void actionStatus(AnchorRequest areq, HttpServletResponse resp)	throws IOException {
		if (areq.user == null) {
			resp.getWriter().println(areq.requireLoginMessage);
		} else {
			resp.getWriter().println("OK: You are logged in as " + areq.user.getEmail());
			resp.getWriter().println(areq.logoutUrl);
			resp.getWriter().println(areq.user.getUserId());
		}
	}

	private void actionSave(AnchorRequest areq, HttpServletResponse resp) throws ServletException, IOException {
		/*
		 * scope "public","private" modelName modelId (om nytt dokument s�
		 * slopar man detta, annars overwrite model med detta id) keywords icon
		 * (textencoded png-image) modelXml
		 * 
		 * returnerar ok/error
		 */

		if (areq.user == null) {
			resp.getWriter().println(areq.requireLoginMessage);
			return;
		}

		String modelName = areq.stringParams.get("modelName");
		String modelXml = areq.stringParams.get("modelXml");
		String icon = areq.stringParams.get("icon");
		String keywords = areq.stringParams.get("keywords");
		String scope = areq.stringParams.get("scope");
		boolean isPublic = (scope != null) && (scope.equalsIgnoreCase("public"));
		String userId = areq.user.getUserId();
		String email = areq.user.getEmail();
		String modelId = areq.stringParams.get("modelId");
		String description = areq.stringParams.get("description");

		PersistenceManager pm = PMF.get().getPersistenceManager();

		Model m;
		if (modelId != null) {
			m = (Model) pm.getObjectById(Model.class, KeyFactory.stringToKey(modelId));
			if (m == null)
				throw new ServletException("Tried to update nonexisting model with id: " + modelId);
			if (!m.getUserId().equals(userId))
				throw new ServletException("A public model can only be updated by the person who created it");
		} else
			m = new Model();

		m.setModelName(modelName);
		m.setModelXml(new Text(modelXml));
		m.setIcon(new Text(icon));
		m.setUserId(userId);
		m.setKeywords(keywords);
		m.setPublic(isPublic);
		m.setEmail(email);
		m.setDescription(new Text(description==null ? "" : StringEscapeUtils.unescapeEcmaScript(description) ));

		try {
			pm.makePersistent(m);
		} finally {
			pm.close();
		}
		resp.getWriter().println("OK: " + KeyFactory.keyToString(m.getKey()));
	}

	private void actionLoad(AnchorRequest areq, HttpServletResponse resp) throws Exception {
		String modelId = areq.stringParams.get("modelId");
		PersistenceManager pm = PMF.get().getPersistenceManager();
		try {
			Key key = KeyFactory.stringToKey(modelId);
			Model m = (Model) pm.getObjectById(Model.class, key);

			if (!m.isPublic()) {
				if ((areq.user == null)	|| !m.getUserId().equals(areq.user.getUserId()))
					throw new ServletException("Cannot load a private model unless logged in as the user that created it");
			}

			//Return xml-model
			resp.getWriter().println(m.getModelXml().getValue());

			//Update statistics
			m.setLastLoaded(new Date());
			m.setLoadCount( new Integer(m.getLoadCount().intValue()+1) );
			pm.makePersistent(m);
			
		} finally {
			pm.close();
		}
	}

	private void actionDelete(AnchorRequest areq, HttpServletResponse resp)	throws Exception {
		String modelId = areq.stringParams.get("modelId");
		PersistenceManager pm = PMF.get().getPersistenceManager();
		try {
			Key key = KeyFactory.stringToKey(modelId);
			Model m = (Model) pm.getObjectById(Model.class, key);

			if ((areq.user == null) || !m.getUserId().equals(areq.user.getUserId()))
				throw new ServletException("Cannot delete a model unless logged in as the user that created it");

			pm.deletePersistent(m);
			resp.getWriter().println("OK: model deleted successfully");
		} finally {
			pm.close();
		}
	}

	@SuppressWarnings("unchecked")
	private void actionList(AnchorRequest areq, HttpServletResponse resp) throws Exception {
		/* Returns a xml-formatted list with modelId, modelName, icon, domain, keywords, description
		 */

		String scope = areq.stringParams.get("scope");
		boolean isPublic = (scope != null) && (scope.contains("public"));
		boolean isPrivate = (scope != null) && (scope.contains("private"));

		if (isPrivate && (areq.user == null)) {
			resp.getWriter().println(areq.requireLoginMessage);
			return;
		}

		String filterBy = areq.stringParams.get("filterBy");
		String filterValue = areq.stringParams.get("filterValue");
		String filterString = null;
		if ((filterBy != null) && (filterValue != null)) {
			filterValue=filterValue.toLowerCase();
			if(filterBy.equals("keywords")) {
				String[] words = filterValue.split(" ");
				List<String> lines = new ArrayList<String>();
				for(String s:words)
					lines.add("keywordList.contains('" + s + "')");
				filterString=StringUtils.join(lines," && ");
			} else
				filterString = filterBy + ".startsWith('" + filterValue + "')";
		}

		String maxItemsReturned = areq.stringParams.get("maxItemsReturned");

		PersistenceManager pm = PMF.get().getPersistenceManager();
		Query query = null;
		try {
			query = pm.newQuery(Model.class);
			
			int maxHits = 20;
			if (maxItemsReturned != null) {
				maxHits = Integer.parseInt(maxItemsReturned);
				if (maxHits > 100)
					throw new ServletException("Max 100 hits allowed");
			}
			query.setRange(0, maxHits);

			String s = null;
			if (isPublic && isPrivate)
				;
			else if (isPublic)
				s = "isPublic == true";
			else if (isPrivate) {
				String userId = areq.user.getUserId();
				s = "(isPublic == false) && (userId == '" + userId + "')";
				//TODO: use query parameters
			}
			if (s != null) {
				filterString = ((filterString == null) ? "" : filterString + " && ") + s;
			}

			if (filterString != null)
				query.setFilter(filterString);
			query.setOrdering("loadCount desc, lastLoaded desc");
			List<Model> results = (List<Model>) query.execute();

			// Format as xml http://www.roseindia.net/servlets/xm-servlet.shtml
			DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = builderFactory.newDocumentBuilder();
			// creating a new instance of a DOM to build a DOM tree.
			Document doc = docBuilder.newDocument();
			Element root = doc.createElement("Models");
			doc.appendChild(root);

			for (Model m : results) {
				Element child = doc.createElement("Model");
				child.setAttribute("modelName", m.getModelName());
				child.setAttribute("modelId", KeyFactory.keyToString(m.getKey()));
				child.setAttribute("icon", m.getIcon().getValue());
				child.setAttribute("keywords", m.getKeywords());
				child.setAttribute("scope", m.isPublic() ? "public" : "private");
				child.setAttribute("userId", m.getUserId());
				child.setAttribute("description", m.getDescription()==null ? "" :  StringEscapeUtils.escapeEcmaScript((m.getDescription().getValue())));
				child.setAttribute("lastLoaded", m.getLastLoaded().toString());
				child.setAttribute("loadedCount", String.valueOf(m.getLoadCount()) );
				root.appendChild(child);
			}

			TransformerFactory factory = TransformerFactory.newInstance();
			Transformer transformer = factory.newTransformer();

			transformer.setOutputProperty(OutputKeys.INDENT, "yes");

			// create string from xml tree
			StringWriter sw = new StringWriter();
			StreamResult result = new StreamResult(sw);
			DOMSource source = new DOMSource(doc);
			transformer.transform(source, result);
			String xmlString = sw.toString();

			resp.getWriter().println(xmlString);

		} finally {
			if (query != null)
				query.closeAll();
			pm.close();
		}

	}

		
}
