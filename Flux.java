// SPDX-License-Identifier: MIT

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Flux
{
	// read the commandline and execute the given script.
	public static void main(String[] args) throws Exception
	{
		if(args.length<1)throw new Exception("a path to a source file must be given.");

		//
		// read the source file.
		// ---------------------
		//
		// the first argument should be the path to the source file.
		//

		Path   source_path;
		byte[] source;

		source_path=Paths.get(args[0]);
		source     =Files.readAllBytes(source_path);

		//
		// create and initialize the executor.
		// -----------------------------------
		//

		int          stack_length;
		FluxExecutor flux_executor;
		FluxStatus   flux_status;

		stack_length=1<<16; // 64k elements.

		flux_executor=new FluxExecutor(source,stack_length);

		//
		// execute the source.
		// -------------------
		//
		// TODO: this should be given a pool of buffers in the rewrite.
		//

		flux_executor.execute();
	}
}
