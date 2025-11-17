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

		int lane_count,buffer_count,buffer_index,dstack_size,jstack_size;
		int[] buffer_sizes;
		int   buffer_size;
		int[] buffer;

		source_path=Paths.get(args[0]);
		source     =Files.readAllBytes(source_path);

		lane_count  =4;
		buffer_count=2;
		buffer_sizes=new int[]{1<<16,1<<16};
		buffer_index=0;
		dstack_size =1<<16;
		jstack_size =1<<16;

		buffer_size=1<<16;
		buffer     =new int[buffer_size/4];

		/*
		flux_executor=new FluxExecutor(source,lane_count,buffer_count,buffer_sizes,buffer_index,dstack_size,jstack_size);
		flux_status  =flux_executor.execute(buffer, buffer_size);
		*/
	}
}
