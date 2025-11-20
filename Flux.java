import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Flux
{
	public static void main(String[] args) throws Exception
	{
		//NOTE: the first arg is the source_path
		assert(args.length>0);

		Path         source_path;
		byte[]       source;
		FluxExecutor flux_executor;
		FluxStatus   flux_status;

		source_path=Paths.get(args[0]);
		source     =Files.readAllBytes(source_path);

		int lane_count,stack_length;

		lane_count  =1<< 2;
		stack_length=1<<16;

		flux_executor=new FluxExecutor(source,lane_count,stack_length);

		int[][] source_buffer_pool=new int[2][1024];

		flux_executor.execute(source_buffer_pool);

		/*
		flux_executor=new FluxExecutor(source,lane_count,buffer_count,buffer_sizes,buffer_index,dstack_size,jstack_size);
		flux_status  =flux_executor.execute(buffer, buffer_size);
		*/
	}
}
