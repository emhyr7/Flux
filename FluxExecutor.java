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

	public static final int WORD_OFFSET_BUILTIN=-1;

	public static final byte CHARACTER_NULL ='\0';
	public static final byte CHARACTER_SPACE=' ';


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

	static int alignr(int a,int b)
	{
		assert((b%2)==0);

		int c=a&(b-1);
		c=c!=0?b-c:0;
		return a+c;
	}

	public FluxExecutor(byte[] source,int lane_count,int data_stack_length,int jump_stack_length)
	{
		this.source=new byte[alignr(1+source.length+64,64)];

		System.arraycopy(source,0,this.source,1,source.length);
		this.source[0]=CHARACTER_NULL;

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

		this.print_source("INITIAL");

		//
		// nullify characters that are classified as a space.
		//
		for(int i=0;i<this.source.length;i+=1)
		{
			byte character;

			character=this.source[i];

			// erase all characters classified as SPACE to NULL.
			// NOTE: a character is classified as SPACE if its value is less-than or equal-to `CHARACTER_SPACE`.
			character=character<=CHARACTER_SPACE?CHARACTER_NULL:character;

			this.source[i]=character;
		}
		this.print_source("SPACE->NULL");

		byte[] word_marks=new byte[this.source.length];

		//
		// mark each word.
		//
		for(int i=0;i<this.source.length-1;i+=1)
		{
			int     batch,marks;
			boolean is_word;

			batch =this.source[i+0]<<(0*8);
			batch|=this.source[i+1]<<(1*8);

			is_word =((batch>>(0*8))&0xFF)==CHARACTER_NULL;
			is_word&=((batch>>(1*8))&0xFF)!=CHARACTER_NULL;

			marks=(is_word?0b11:0)<<((i+1)%8);

			word_marks[(i+1)/8+0]|=(byte)((marks>>(0*8))&0xFF);
			word_marks[(i+1)/8+1]|=(byte)((marks>>(1*8))&0xFF);
		}
		this.print_source("MARK WORDS");

		// NOTE: we must ensure that the next space of a single-letter word is
		// included.
		
		// NOTE: we also must ensure the letters pass the first two characters of a
		// word are ignored.



		//
		// replace the next byte of a single-letter word with a space.
		//
		for(int i=0;i<this.source.length-3;i+=1)
		{
			int     batch;
			byte    marks;
			boolean is_single;

			// load a batch of 4 bytes as a 64-bit WORD.
			batch =this.source[i+0]<<(0*8);
			batch|=this.source[i+1]<<(1*8);
			batch|=this.source[i+2]<<(2*8);
			batch|=this.source[i+3]<<(3*8); // NOTE: this last byte is extarneous.

			// check if the batch contains a single-character word.
			is_single =((batch>>(0*8))&0xFF)==CHARACTER_NULL;
			is_single|=((batch>>(0*8))&0xFF)==CHARACTER_SPACE;
			is_single&=((batch>>(1*8))&0xFF)!=CHARACTER_NULL;
			is_single&=((batch>>(2*8))&0xFF)==CHARACTER_NULL;

			// set the byte after the single-character to SPACE, if applicable.
			batch|=is_single?(CHARACTER_SPACE<<(2*8)):0;

			this.source[i+0]=(byte)(batch>>(0*8));
			this.source[i+1]=(byte)(batch>>(1*8));
			this.source[i+2]=(byte)(batch>>(2*8));
			this.source[i+3]=(byte)(batch>>(3*8));
		}
		this.print_source("ADD SPACE TO SINGLE-LETTER WORDS");

		//
		// exclude letters past the first two letters of each word.
		//
		for(int i=1;i<this.source.length;i+=1)
		{
			int character,marks;
			boolean mark;

			marks=word_marks[i/8];
			mark =(marks&(1<<(i%8)))!=0;

			character=this.source[i];
			character=mark?character:CHARACTER_NULL;

			this.source[i]=(byte)character;
		}

		this.print_source("IGNORE WORDS WITH MORE THAN 2 LETTERS");

		boolean sorted;
		do{
			boolean swap;
			byte a,b,c;

			sorted=true;
			for(int i=1;i<this.source.length-1;i+=2)
			{
				swap=(this.source[i]==CHARACTER_NULL?1:0)>(this.source[i+1]==CHARACTER_NULL?1:0);

				a=this.source[i+0];
				b=this.source[i+1];
				this.source[i+(swap?1:0)]=a;
				this.source[i+(swap?0:1)]=b;

				sorted=swap?false:sorted;
			}

			for(int i=0;i<this.source.length-1;i+=2)
			{
				swap=(this.source[i]==CHARACTER_NULL?1:0)>(this.source[i+1]==CHARACTER_NULL?1:0);

				a=this.source[i+0];
				b=this.source[i+1];
				this.source[i+(swap?1:0)]=a;
				this.source[i+(swap?0:1)]=b;

				sorted=swap?false:sorted;
			}
		}while(!sorted);

		this.print_source("SORTED");

		// nullify the extraneous letters after the first two characters of a word.
		// NOTE: this will be done serially for convenience sake (skill issue)...
		// but this is parallelizable.


		// all i need to is mark the first letter of a sequence of letters.

		// include next byte after the marked bytes.
		//TODO: sort such that the null bytes are moved towards the back.


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

	private void print_source(byte[] buffer, String pass)
	{
		System.out.println("\n#### "+pass);
		for(int i=0;i<buffer.length;i+=1)
		{
			byte c=buffer[i];
			if(c!=0)System.out.print((char)c);
			else System.out.print('\\');
		}
		System.out.println();
	}

	private void print_source(String pass)
	{
		this.print_source(this.source,pass);
	}
}
