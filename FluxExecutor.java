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
		SKIP,
	}


////  CONSTANTS  ///////////////////////////////////////////////////////////////

	public static final int PADDING_SIZE=1<<6; // 64 B

	public static final int WORD_COUNT_MAX=1<<16; // 64 kB
	public static final int WORD_SIZE_MAX =1<< 1; // 2   B

	public static final byte CHARACTER_SPACE=' ';

	public static final int WORD_OFFSET_BUILTIN=-1;

////  PROPERTIES  //////////////////////////////////////////////////////////////

	private byte[] source;

	private int[][] lane_data_stack_table;
	private int[]   lane_jump_stack;

	private Thread[]      thread_pool;
	private CyclicBarrier thread_pool_barrier;

	private int[] word_offset_table=new int[WORD_COUNT_MAX];

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

		//enter builtin words.
		for(short word_index:BUILTIN_WORD_INDEX_LIST)
		{
			this.word_offset_table[word_index]=WORD_OFFSET_BUILTIN;
		}

		//enter builtin words: hexadecimals.
		for(int i=0;i<0xFF;i+=1)
		{
			int d,a,b,l,h,v;

			//NOTE: probably faster to load from a read-only table instead of compute.
			//it would fit within L1d$; latency of ~3 cycles per load.

			//lower half
			d=(i&0x0F)>>0;
			a='0'+d%10;
			b='A'+d% 6;
			l=d/10>0?b:a;

			//higher half
			d=(i&0xF0)>>4;
			a='0'+d%10;
			b='A'+d% 6;
			h=d/10>0?b:a;

			//combine
			v=(l<<0)|(h<<8);

			//store
			this.word_offset_table[v]=WORD_OFFSET_BUILTIN;
		}

		/* NOTE: confirm that the builtin words are entered
		for(int i=0;i<WORD_COUNT_MAX;i+=1)
		{
			if(word_offset_table[i]==WORD_OFFSET_BUILTIN)System.out.println("" + (char)(i&0xFF) + " " + (char)((i&0xFF00)>>8));
		}
		*/
	}

	public Status execute(int[][] source_buffer_pool)
	{
		Status status;

		status=Status.SUCCESSFUL;


		// compress into a sequence of 


		// identify characters that are letters.

		for(int i=0;i<this.source.length;i+=1)
		{
			byte character;

			character=this.source[i];
			character=character<CHARACTER_SPACE?CHARACTER_NULL:character;

			this.source[i]=character;
		}

		//
		// identify single-letter words
		//
		// to compress words together, and eliminate extraneous spaces.
		//


		byte word_marks=new byte[this.source.length];


		// NOTE: we must ensure that the next space of a single-letter word is
		// included.
		
		// NOTE: we also must ensure the letters pass the first two characters of a
		// word are ignored.

		for(int i=0;i<this.source.length;i+=1)
		{
			int batch;
			boolean is_single_letter;

			// load 4 bytes as 64-bit word
			batch=this.source[i+0]<< 0;
			batch=this.source[i+1]<< 8;
			batch=this.source[i+2]<<16;
			batch=this.source[i+4]<<24;

			// check if the batch contains a single letter
			is_single_letter =(batch>> 0)&0xFF)==CHARACTER_NULL;
			is_single_letter&=(batch>> 8)&0xFF)!=CHARACTER_NULL;
			is_single_letter&=(batch>>16)&0xFF)==CHARACTER_NULL;

			// ...
			batch|=is_single_letter?CHARACTER_SPACE<<16:0;

			// store
			this.source[i+0]=(batch>> 0)&0xFF;
			this.source[i+1]=(batch>> 8)&0xFF;
			this.source[i+2]=(batch>>16)&0xFF;
			this.source[i+3]=(batch>>24)&0xFF;
		}

		// nullify the extraneous letters after the first two characters of a word.
		// NOTE: this will be done serially for convenience sake (skill issue)...
		// but this is parallelizable.


		// all i need to is mark the first letter of a sequence of letters.

		// mark all letters.
		// if the previous character is not a letter, keep the letter marked.
		// otherwise, unmark it.
		
		for(int i=0;i<this.source.length;i+=1)
		{
			int batch;
			boolean do_mark;

			batch=this.source[i+0]<< 0;
			batch=this.source[i+1]<< 8;
			batch=this.source[i+2]<<16;
			batch=this.source[i+4]<<24;

			do_mark =((batch>> 0)&0xFF)==CHARACTER_NULL;
			do_mark&=((batch>> 8)&0xFF)!=CHARACTER_NULL;

			
		}

		//TODO: sort such that the null bytes are moved towards the back.
		for(int i=0;i<this.source.length;i+=1)
		{

		}


		/*

		int lane_index,lane_data_stack_offset,lane_jump_stack_offset,current_source_position;

		BuiltinWord builtin_word;

		builtin_word=BuiltinWord.LD;

		lane_data_stack_offset=lane_jump_stack_offset=0;

		lane_index=0;
		current_source_position=0;



		word=get_word();

		new_positoin=dictionary[word];

		current_positoin=new_positoin;

		//TODO: do this before executing each word:
		//  this.lane_jump_stack[lane_jump_stack_offset++]=current_source_position;

		//recursively derive the builtin words.

		// execute the builtin word.

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
		case BuiltinWord.SKIP:
			{
				//TODO: skip until after the ;

				do
				{
					word=next_word();
				}
				while(word!=BuiltinWord.BACK);

				next_word();

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

			*/
		return status;
	}
}
