import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the tree.xml written by Parser.
 *
 * Checks: root tag is <tree>, every <node> has a unique id, exactly one
 * root, every child id resolves, every child's <parent> points back to
 * its parent, every parent's <children> lists the child, and the whole
 * graph is reachable from the root exactly once (no cycles, no orphans).
 *
 * usage: java TreeChecker [tree.xml]
 */
public final class TreeChecker {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tree.xml";
        try {
            check(path);
            System.out.println("OK: " + path);
        } catch (AssertionError e) {
            System.err.println("FAIL: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void check(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(path));
        Element root = doc.getDocumentElement();
        if (!"tree".equals(root.getTagName()))
            throw new AssertionError("root tag is '" + root.getTagName() + "', expected 'tree'");

        List<Element> nodes = childrenByName(root, "node");
        if (nodes.isEmpty())
            throw new AssertionError("no <node> elements under <tree>");

        Map<Integer, Element> byId = new HashMap<>();
        List<Element> roots = new ArrayList<>();
        for (Element n : nodes) {
            String idS = n.getAttribute("id");
            if (idS == null || idS.isEmpty())
                throw new AssertionError("<node> missing id attribute");
            int id;
            try { id = Integer.parseInt(idS); }
            catch (NumberFormatException e) {
                throw new AssertionError("non-numeric id '" + idS + "'");
            }
            if (byId.put(id, n) != null)
                throw new AssertionError("duplicate id " + id);

            if (childByName(n, "contents") == null)
                throw new AssertionError("node " + id + " has no <contents>");

            Element ch  = childByName(n, "children");
            Element par = childByName(n, "parent");
            if (ch == null && par == null)
                throw new AssertionError("node " + id + " has neither <children> nor <parent>");
            if (par == null) roots.add(n);
        }

        if (roots.size() != 1)
            throw new AssertionError("expected exactly 1 root, got " + roots.size());

        for (Element n : nodes) {
            int id = Integer.parseInt(n.getAttribute("id"));
            Element ch  = childByName(n, "children");
            Element par = childByName(n, "parent");

            if (ch != null) {
                for (int cid : parseIds(textOf(ch))) {
                    Element c = byId.get(cid);
                    if (c == null)
                        throw new AssertionError(
                            "node " + id + " references missing child " + cid);
                    Element cpar = childByName(c, "parent");
                    if (cpar == null)
                        throw new AssertionError(
                            "child " + cid + " of " + id + " has no <parent>");
                    int pid = Integer.parseInt(textOf(cpar).trim());
                    if (pid != id)
                        throw new AssertionError(
                            "child " + cid + "'s parent is " + pid + ", expected " + id);
                }
            }
            if (par != null) {
                int pid = Integer.parseInt(textOf(par).trim());
                Element p = byId.get(pid);
                if (p == null)
                    throw new AssertionError(
                        "node " + id + "'s parent " + pid + " does not exist");
                Element pch = childByName(p, "children");
                if (pch == null)
                    throw new AssertionError(
                        "parent " + pid + " of " + id + " has no <children>");
                if (!parseIds(textOf(pch)).contains(id))
                    throw new AssertionError(
                        "node " + id + " not listed in parent " + pid + "'s <children>");
            }
        }

        Set<Integer> seen = new HashSet<>();
        int rootId = Integer.parseInt(roots.get(0).getAttribute("id"));
        walk(rootId, byId, seen);
        if (!seen.equals(byId.keySet())) {
            Set<Integer> missing = new HashSet<>(byId.keySet());
            missing.removeAll(seen);
            throw new AssertionError("unreachable nodes: " + missing);
        }
    }

    private static void walk(int id, Map<Integer, Element> byId, Set<Integer> seen) {
        if (!seen.add(id))
            throw new AssertionError("node " + id + " visited twice (cycle or duplicate child)");
        Element n = byId.get(id);
        Element ch = childByName(n, "children");
        if (ch == null) return;
        for (int cid : parseIds(textOf(ch))) walk(cid, byId, seen);
    }

    private static List<Integer> parseIds(String s) {
        List<Integer> out = new ArrayList<>();
        for (String p : s.trim().split(",")) {
            if (!p.isBlank()) out.add(Integer.parseInt(p.trim()));
        }
        return out;
    }

    private static Element childByName(Element parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && name.equals(k.getNodeName()))
                return (Element) k;
        }
        return null;
    }

    private static List<Element> childrenByName(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && name.equals(k.getNodeName()))
                out.add((Element) k);
        }
        return out;
    }

    private static String textOf(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE
             || k.getNodeType() == Node.CDATA_SECTION_NODE)
                sb.append(k.getNodeValue());
        }
        return sb.toString();
    }
}