package eu.mihosoft.vmf.core.io;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Hashtable;

public class MemoryResourceSet implements ResourceSet {

    private Hashtable<String, MemoryResource> memSet = new Hashtable<>();

    @Override
    public Resource open(String url) {
        if (memSet.containsKey(url)) return memSet.get(url);
        else {
            MemoryResource res = new MemoryResource();
            memSet.put(url, res);
            return res;
        }
    }

    public Hashtable<String, MemoryResource> getMemSet() {
        return memSet;
    }

    public String asString() {
        StringWriter out = new StringWriter();
        for (String url : memSet.keySet()) {
            out.write("\n");
            out.write("--------------------------------------------------------------------------------\n");
            out.write("ENTRY " + url+"\n");
            out.write("--------------------------------------------------------------------------------\n");
            out.write(memSet.get(url).asString());
        }
        return out.toString();
    }

    public void printStats(PrintWriter out) {
        out.println("Resource count: " + memSet.keySet().size());
        out.println("Resource urls: ");
        for (String url : memSet.keySet()) {
            out.println("- " + url + " (" + memSet.get(url).mem.size() + ")");
        }
    }
}
