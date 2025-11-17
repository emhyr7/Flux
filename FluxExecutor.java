import java.util.concurrent.CyclicBarrier;

public class FluxExecutor
{

	public enum Status
	{
		SUCCESSFUL,
	}

	public enum BuiltinWord
	{
		ST,
		LD,
		ADD,
		SUB,
		MUL,
		DIV,
		SYNC,
		BACK,
		JUMP,
	}


////  CONSTANTS  ///////////////////////////////////////////////////////////////

	public static final int PADDING_SIZE=1<<6; // 64 B

	public static final int WORD_COUNT_MAX=1<<16; // 64 kB
	public static final int WORD_SIZE_MAX =1<< 1; // 2   B

	public static final byte CHARACTER_SPACE=' ';

	public static final int WORD_INDEX_BUILTIN=-1;

////  PROPERTIES  //////////////////////////////////////////////////////////////

	private byte[] source;

	private int[][] lane_data_stack_table;
	private int[]   lane_jump_stack;

	private Thread[]      thread_pool;
	private CyclicBarrier thread_pool_barrier;

	private int[] word_caret_table=new int[WORD_COUNT_MAX];

	private final short[] BUILTIN_WORD_INDEX_LIST=new short[]
	{
		('.'<<0)|(' '<<8), // store
		('@'<<0)|(' '<<8), // load
		('='<<0)|(' '<<8), // synchronize
		(':'<<0)|(' '<<8), // branch
		(';'<<0)|(' '<<8), // return
		('+'<<0)|(' '<<8), // add
		('-'<<0)|(' '<<8), // subtract
		('*'<<0)|(' '<<8), // multiply
		('/'<<0)|(' '<<8), // divide
		('%'<<0)|(' '<<8), // remainder
		('$'<<0)|(' '<<8), // print [monayyy]
	};

////  METHODS  /////////////////////////////////////////////////////////////////

	public FluxExecutor(byte[] source,int lane_count,int data_stack_length,int jump_stack_length)
	{
		this.source=source;

		this.lane_data_stack_table=new int[lane_count][PADDING_SIZE+data_stack_length+PADDING_SIZE];
		this.lane_jump_stack      =new int[PADDING_SIZE+jump_stack_length+PADDING_SIZE];

		this.thread_pool        =new Thread[lane_count];
		this.thread_pool_barrier=new CyclicBarrier(lane_count);

		// TODO: initialize the word index table with builtin words.

		for(short word_index:BUILTIN_WORD_INDEX_LIST)
		{
			this.word_caret_table[word_index]=0;
		}
	}

	public Status execute(int[][] source_buffer_pool)
	{
		Status status;

		status=Status.SUCCESSFUL;

		int lane_index,lane_data_stack_offset,lane_jump_stack_offset,current_source_position;

		BuiltinWord builtin_word;

		builtin_word=BuiltinWord.LD;

		lane_data_stack_offset=lane_jump_stack_offset=0;

		lane_index=0;
		current_source_position=0;

		switch(builtin_word)
		{
		case BuiltinWord.ST:
			{
				int source_buffer_index,source_buffer_offset,store_value;

				source_buffer_index =this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				source_buffer_offset=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				store_value         =this.lane_data_stack_table[lane_index][--lane_data_stack_offset];

				source_buffer_pool[source_buffer_index][source_buffer_offset]=store_value;

				break;
			}
		case BuiltinWord.LD:
			{
				int source_buffer_index,source_buffer_offset,load_value;

				source_buffer_index =this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				source_buffer_offset=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];

				load_value=source_buffer_pool[source_buffer_index][source_buffer_offset];

				this.lane_data_stack_table[lane_index][lane_data_stack_offset++]=load_value;

				break;
			}
		case BuiltinWord.ADD:
			{
				int value_a,value_b,value_c;

				value_a=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_b=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_c=value_a+value_b;

				this.lane_data_stack_table[lane_index][lane_data_stack_offset++]=value_c;

				break;
			}
		case BuiltinWord.SUB:
			{
				int value_a,value_b,value_c;

				value_a=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_b=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_c=value_a-value_b;

				this.lane_data_stack_table[lane_index][lane_data_stack_offset++]=value_c;

				break;
			}
		case BuiltinWord.MUL:
			{
				int value_a,value_b,value_c;

				value_a=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_b=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_c=value_a*value_b;

				this.lane_data_stack_table[lane_index][lane_data_stack_offset++]=value_c;

				break;
			}
		case BuiltinWord.DIV:
			{
				int value_a,value_b,value_c;

				value_a=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_b=this.lane_data_stack_table[lane_index][--lane_data_stack_offset];
				value_c=value_a/value_b;

				this.lane_data_stack_table[lane_index][lane_data_stack_offset++]=value_c;

				break;
			}
		case BuiltinWord.SYNC:
			{
				try
				{
					this.thread_pool_barrier.await();
				}
				catch(Exception e)
				{
					// ignore.
				}

				break;
			}
		case BuiltinWord.JUMP:
			{
				try
				{
					this.thread_pool_barrier.await();
				}
				catch(Exception e)
				{
					// ignore.
				}

				this.lane_jump_stack[lane_jump_stack_offset++]=current_source_position;

				break;
			}
		case BuiltinWord.BACK:
			{
				int back_position;

				back_position=this.lane_jump_stack[--lane_jump_stack_offset];

				current_source_position=back_position;

				break;
			}
		}

		return status;
	}
}
