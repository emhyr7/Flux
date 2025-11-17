import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;

//
//
//
//
//
//branches are prevented by not allowing the instruction pointer to be changed
//by input data at runtime.
//
//branchlessness is important because lanes work in lockstep.
//
//
//
//
//

//
//
//  THE MODEL  /////////////////////////////////////////////////////////////////
//
//
//a set of buffers.
//a program.
//a group of lanes.
//
//upon execution, each lane is evenly dispatched in the input buffer and
//executes the program in lockstep.
//
//lanes are evenly pinned to physical cores. e.g., if we have 8 lanes and 4
//cores, each core will have 2 lanes. if there is an odd number of lanes, then
//some cores may have an odd number of lanes. the user should avoid an uneven
//number of lanes.
//
//
//
//a data-stack (dstack) per lane.
//a jump-stack (jstack).
//a dictionary.
//
//a dstack stores the data being operated on.
//the jstack stores the location of each caller for after the callee finishes.
//
//the dictionary is built before execution and becomes read-only upon execution.
//there is only 1 jstack because all lanes execute in lockstep, therefore, there
//is no reason for a jstack per lane.
//
//
//
//data is stored via the word `@`.
//
//`@` requires 3 arguments: a buffer index then an element index then a value:
//
//	43 45 00 @
//
//this stores hexadecimal 43 to element 45 of buffer 00.
//
//upon the completetion of the program, all buffers are dumped and the dstack
//and jstack are trashed.
//
//

//
//NOTE: we assume that the source is encoded in little-endian ASCII.
//


//
//TODO: load 4 bytes per iteration rather than 3 separate bytes.
//
//no (0):
//
//	d[0]=s[i+0];
//	d[1]=s[i+1];
//	d[2]=s[i+2];
//
//do (1):
//
//	d =0;
//	d|=s[i+0]<< 0;
//	d|=s[i+1]<< 8;
//	d|=s[i+2]<<16;
//	d|=s[i+3]<<24;
//
//or (2):
//
//	d=ByteBuffer.wrap(s).getInt(i);
//
//---
//
//(1) is may be optimized to a simple 4-byte load.
//(2) creates an entirely new instance of a class - no likey.
//

public class FluxExecutor
{

////////////////////////////////////////////////////////////////////////////////
//  CONSTANTS
////////////////////////////////////////////////////////////////////////////////

	//
	//LIMITS
	//

	public static final int SOURCE_ALIGNMENT=1<< 6;// 64
	public static final int WORD_COUNT_MAX  =1<<16;// 64 k
	public static final int WORD_SIZE_MAX   =1<< 1;//  2
	public static final int GRANULARITY     =1<< 2;//  4

	//
	//CHARACTERS
	// 

	public static final byte CHARACTER_SPACE=' ';
	public static final byte CHARACTER_ONSET=':';

	//
	//WORDS
	//

	//TODO: it is better if we reversed the order of the characters.
	//instead of the space being the second character, the space should
	//be the first character. this would mean we need to swap bytes for
	//every index, but this would also give better cache-locality for
	//builtin words.
	public static final short[] WORDS=new short[]
	{
		('@'<<0)|(' '<<8),//switch buffer
		('='<<0)|(' '<<8),//synchronize
		(';'<<0)|(' '<<8),//return
		('+'<<0)|(' '<<8),//add
		('-'<<0)|(' '<<8),//subtract
		('*'<<0)|(' '<<8),//multiply
		('/'<<0)|(' '<<8),//divide
		('%'<<0)|(' '<<8),//remainder
		('$'<<0)|(' '<<8),//print [monayyy]
	};

	public static final int WORD_CARET_BUILTIN  =-1;
	public static final int WORD_CARET_UNDEFINED= 0;

////////////////////////////////////////////////////////////////////////////////
//  PROPERTIES
////////////////////////////////////////////////////////////////////////////////

	private byte[] source;
	private int    lane_count;
	private int    buffer_count;
	private int[]  buffer_sizes;
	private int    dstack_size;
	private int    jstack_size;

	private int     buffer_index;
	private int[][] buffers;
	private int[][] dstacks;
	//only 1 jstack because all the lanes work in lockstep.
	private int[]   jstack;

	private int[] dictionary=new int[WORD_COUNT_MAX];
 

	private int[]         buffer;
	private Thread[]      threads;
	private CyclicBarrier barrier;

////////////////////////////////////////////////////////////////////////////////
//  PUBLIC METHODS
////////////////////////////////////////////////////////////////////////////////

	public FluxExecutor(byte[] source,int lane_count,int buffer_count,int[] buffer_sizes,int buffer_index,int dstack_size,int jstack_size)
	{
		int capacity;

		//`this.source` is padded on both ends with at least `SOURCE_ALIGNMENT`
		//bytes
		capacity=SOURCE_ALIGNMENT+source.length+SOURCE_ALIGNMENT;
		capacity=alignr(capacity,SOURCE_ALIGNMENT);

		this.source=new byte[capacity];
		
		Arrays.fill(this.source,CHARACTER_SPACE);
		System.arraycopy(source,0,this.source,SOURCE_ALIGNMENT,source.length);

		this.lane_count  =lane_count;
		this.buffer_count=buffer_count;
		this.buffer_sizes=buffer_sizes;
		this.buffer_index=buffer_index;
		this.dstack_size =dstack_size;
		this.jstack_size =jstack_size;

		this.buffers=new int[this.buffer_count][];

		for(int i=0;i<this.buffer_count;i+=1)
		{
			this.buffers[i]=new int[this.buffer_sizes[i]/GRANULARITY];
		}

		this.dstacks=new int[this.lane_count][this.dstack_size/GRANULARITY];
		this.jstack =new int[this.jstack_size/GRANULARITY];

		this.threads=new Thread[this.lane_count];

		this.barrier=new CyclicBarrier(this.lane_count,()->System.out.println("SYNC!"));
	}

	public FluxStatus execute(int[] buffer,int buffer_size)
	{
		FluxStatus status;

		status=FluxStatus.SUCCESSFUL;

		//TODO: SIMD acceleration

		//
		//transform all non-printable characters into `CHARACTER_SPACE`.
		//

		for(int i=SOURCE_ALIGNMENT;i<this.source.length-SOURCE_ALIGNMENT;i+=1)
		{
			byte    character;
			boolean do_transform;

			character=this.source[i];

			//from: https://www.ascii-code.com/
			do_transform =character<=0x1F;
			do_transform|=character==0x7F;

			character=do_transform?CHARACTER_SPACE:character;

			this.source[i]=character;
		}

		//
		//build dictionary.
		//

		for(int i=SOURCE_ALIGNMENT;i<this.source.length-SOURCE_ALIGNMENT-1;i+=1)
		{
			byte[]  characters=new byte[3];
			boolean is_onset,is_word,do_enter,is_entered;
			int     word_index,word_caret;

			//NOTE: probably better to load 4 bytes per iteration and work with 64
			//bits.
			characters[0]=this.source[i-1];
			characters[1]=this.source[i  ];
			characters[2]=this.source[i+1];

			is_onset =characters[0]==CHARACTER_SPACE;
			is_onset&=characters[1]==CHARACTER_ONSET;
			is_onset&=characters[2]==CHARACTER_SPACE;

			is_entered=false;

			for(int j=SOURCE_ALIGNMENT;j<this.source.length-SOURCE_ALIGNMENT;j+=1)
			{
				characters[0]=this.source[j-1];
				characters[1]=this.source[j  ];
				characters[2]=this.source[j+1];

				is_word =characters[0]==CHARACTER_SPACE;
				is_word&=characters[1]!=CHARACTER_SPACE;

				word_index =characters[1]<<0;
				word_index|=characters[2]<<8;

				word_caret=this.dictionary[word_index];

				do_enter   =j>i&&is_word&&!is_entered&&is_onset&&word_caret==WORD_CARET_BUILTIN;
				is_entered|=do_enter;
				word_caret=do_enter?j:word_caret;

				this.dictionary[word_index]=word_caret;
			}
		}

		//
		//enter builtin words.
		//
		//this includes elementary operations and numbers.
		//

		this.buffer=buffer;

		//enter specific words.
		for(int i=0;i<WORDS.length;i+=1)
		{
			int word_caret;


			word_caret=WORDS[i];


			this.dictionary[word_caret]=-1;
		}

		//enter numbers.
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
			this.dictionary[v]=-1;
		}

		//dispatch threads.
		for(int i=0;i<this.lane_count;i+=1)
		{
			final int index=i;
			this.threads[i]=new Thread(()->execute_lane(this,index));
			this.threads[i].start();
		}

		/*
		for(int i=0;i<this.dictionary.length;i+=1)
		{
			int v=this.dictionary[i];
			if(v!=0)System.out.println(""+(v!=-1?v-SOURCE_ALIGNMENT:v)+"\t"+(char)(i&0xff)+""+(char)((i&0xff00)>>8));
		}

		for(int i=SOURCE_ALIGNMENT;i<this.source.length-SOURCE_ALIGNMENT;i+=1)
			System.out.print(""+(char)this.source[i]);
		*/

		return status;
	}

	private static int execute_lane(FluxExecutor self,int lane_index)
	{
		int     element,element_index_start,element_index_end,caret,word_caret;
		boolean exit,is_word,is_builtin_word;

		int word_definition_index,builtin_word_caret;

		element_index_start=self.buffer.length/self.lane_count;
		element_index_end  =self.buffer.length%self.lane_count;

		exit=false;

		do
		{
			for(int element_index=element_index_start;element_index<element_index_end;element_index+=1)
			{
				//initialize and synchronize all lanes
				element=self.buffer[element_index];
				caret=SOURCE_ALIGNMENT;
				try{self.barrier.await();}catch(Exception e){};

				//process element
				for(;;)
				{

					//scan next word.

					characters[0]=this.source[caret+0];
					characters[1]=this.source[caret+1];
					characters[2]=this.source[caret+2];

					is_word =characters[0]==CHARACTER_SPACE;
					is_word&=characters[1]!=CHARACTER_SPACE;

					word_caret =characters[1]<<0;
					word_caret|=characters[2]<<8;

					word_caret=this.dictionary[word_index];

					builtin_word_caret=-1;
					for(int i=0;i<WORDS.length;i+=1)
					{
						is_builtin_word=WORDS[i]==word_index;
						builtin_word_caret=is_builtin_word?i:builtin_word_caret;
					}

					switch(builtin_word_caret)
					{
					case :
					default:
						//
						break;
					}
				}
			}
		}
		while(!exit);

		return 0;
	}

////////////////////////////////////////////////////////////////////////////////
//  PRIVATE METHODS
////////////////////////////////////////////////////////////////////////////////

	private int alignr(int value,int alignment)
	{
		assert(alignment%2==0);

		int result;

		result=value%(alignment-1);
		result=result!=0?alignment-result:0;
		result=value+result;

		return result;
	}
}
