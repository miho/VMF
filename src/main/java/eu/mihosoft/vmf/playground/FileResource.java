package eu.mihosoft.vmf.playground;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FileResource implements Resource {
	
	final File file;
	FileWriter fileWriter;
	
	public FileResource(File file)
	{
		this.file = file;
	}
	
	public File getFile()
	{
		return this.file;
	}

	@Override
	public PrintWriter open() throws IOException {
		return new PrintWriter(fileWriter = new FileWriter(file));
	}

	@Override
	public void close() throws IOException{
		fileWriter.close();
	}

}
