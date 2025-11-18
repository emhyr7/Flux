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

		int lane_count,data_stack_length,jump_stack_length;

		lane_count       =1<< 2;
		data_stack_length=1<<16;
		jump_stack_length=1<<16;

		flux_executor=new FluxExecutor(source,lane_count,data_stack_length,jump_stack_length);

		/*
		flux_executor=new FluxExecutor(source,lane_count,buffer_count,buffer_sizes,buffer_index,dstack_size,jstack_size);
		flux_status  =flux_executor.execute(buffer, buffer_size);
		*/
	}
}
