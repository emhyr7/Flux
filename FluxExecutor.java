// SPDX-License-Identifier: MIT

// TODO: use a `ByteBuffer` instead of a `byte[]`

public class FluxExecutor
{
	//
	// the padding is 64 bytes since 64 bytes is the typical size of a cacheline
	// and the granularity at which destructive interference between cores is met.
	//
	public static final int PADDING_SIZE=1<<6; // 64 B

	//
	// the limitations of words.
	// -------------------------
	//
	// the size of words are limited to 2 bytes (i.e., 2 characters), where extra
	// characters are ignored, which may be used as a commenting feature.
	//
	// since words are limited to 2 bytes, which is 2*8=16 bits, we have 64k
	// possible words. the actual limit is far lesser since we interpret certain
	// characters in a specialized manner.
	//
	public static final int WORD_SIZE_MAX =1<< 1; // 2   B
	public static final int WORD_COUNT_MAX=1<<16; // 64 kB

	//
	// `WORD_OFFSET_UNDEFINED` being 0 doesn't cause harm because there must be a
	// `:` to define a word; therefore, a word's offset should never be 0.
	//
	public static final int WORD_OFFSET_UNDEFINED= 0;

	//
	// `WORD_OFFSET_BUILTIN` can be `-1` because a source of that size is crazy.
	//
	public static final int WORD_OFFSET_BUILTIN  =-1;

	//
	// special characters.
	// -------------------
	//
	public static final byte CHARACTER_NULL ='\0';
	public static final byte CHARACTER_SPACE=' ';

	//
	// builtin words.
	// --------------
	//
	// at the moment, there are some extraneous words that aren't yet implemented.
	//
	// `BUILTIN_WORD_INDEX_LIST` is just to easily initialize the builtin words
	// therein.
	//

	private static final short BUILTIN_WORD_ST  =('.'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_LD  =('@'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_SYNC=('='<<0)|(' '<<8);
	private static final short BUILTIN_WORD_DEF =(':'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_RET =(';'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_ADD =('+'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_SUB =('-'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_MUL =('*'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_DIV =('/'<<0)|(' '<<8);
	private static final short BUILTIN_WORD_REM =('%'<<0)|(' '<<8);

	private static final short[] BUILTIN_WORD_INDEX_LIST=new short[]
	{
		BUILTIN_WORD_ST,
		BUILTIN_WORD_LD,
		BUILTIN_WORD_SYNC,
		BUILTIN_WORD_DEF,
		BUILTIN_WORD_RET,
		BUILTIN_WORD_ADD,
		BUILTIN_WORD_SUB,
		BUILTIN_WORD_MUL,
		BUILTIN_WORD_DIV,
		BUILTIN_WORD_REM,
	};

	//
	// properties.
	// -----------
	//

	private byte[] source;

	private short[] word_buffer;
	private int     word_count;
	private int[]   word_table=new int[WORD_COUNT_MAX];

	private int[] stack;
	private int   stack_mass;


	//
	// align `a` to the next nearest boundary of `b`. 
	//
	private static int alignr(int a,int b)
	{
		assert((b%2)==0);

		int c=a&(b-1);
		c=c!=0?b-c:0;
		return a+c;
	}

	//
	// `source` should be encoded in ASCII, where characters of a value less
	// than 32 (i.e., the "space" character) are interpreted as 32.
	//
	// `stack_length` is the number of elements on the stack, where each element
	// is a 4-byte integer.
	//
	public FluxExecutor(byte[] source,int stack_length)
	{
		//
		// initialize the source.
		// ----------------------
		//
		// the initial `1+` to the source's length, that which holds the initial
		// NULL character, is to ensure the first word is detectable by the
		// "NULL, non-NULL" pattern that every other word will possess.
		//
		this.source=new byte[alignr(1+source.length+PADDING_SIZE,PADDING_SIZE)];
		System.arraycopy(source,0,this.source,1,source.length);
		this.source[0]=CHARACTER_NULL;

		//
		// initialize the word buffer.
		// ---------------------------
		//
		// each word is 2 bytes, which is twice the size of a character in the
		// source, hence the `/2`.
		//
		// the maximum number of words will always be less-than the length of the
		// source because we never introduce additional data.
		//
		this.word_buffer=new short[this.source.length/2];
		this.word_count =0;

		//
		// initialize the stack.
		// ---------------------
		//
		this.stack     =new int[stack_length+PADDING_SIZE];
		this.stack_mass=0;

		//
		// initialize the word_table.
		// ---------------------------------
		//
		for(int i=0;i<this.word_table.length;i+=1)
		{
			this.word_table[i]=WORD_OFFSET_UNDEFINED;
		}

		//
		// initialize the non-numerical builtin words.
		// -------------------------------------------
		//
		for(short word_index:BUILTIN_WORD_INDEX_LIST)
		{
			this.word_table[word_index]=WORD_OFFSET_BUILTIN;
		}

		//
		// initialize the numerical builtin words.
		// ---------------------------------------
		//
		// numbers are in a 2-digit, capitalized, hexadecimal form.
		//
		for(int i=0;i<0xFF;i+=1)
		{
			int d,a,b,l,h,v;

			// NOTE: this is a very hacky solution.

			// NOTE: probably faster to load from a read-only table instead of compute.
			// it would fit within L1d$; latency of ~3 cycles per load, which is far
			// less than the potential ~>10 cycles.

			// translate the lower half.
			d=(i&0x0F)>>0;
			a='0'+d%10;
			b='A'+d% 6;
			l=d/10>0?b:a;

			// translate the higher half.
			d=(i&0xF0)>>4;
			a='0'+d%10;
			b='A'+d% 6;
			h=d/10>0?b:a;

			// combine.
			v=(l<<0)|(h<<8);

			// store.
			this.word_table[v]=WORD_OFFSET_BUILTIN;
		}
	}

	//
	// compile and execute the source.
	// -------------------------------
	//
	public FluxStatus execute(int[][] source_buffer_pool) throws Exception
	{
		FluxStatus status;

		status=FluxStatus.SUCCESSFUL;

		//
		// nullify characters that are classified as a space.
		// --------------------------------------------------
		//
		// a character is classified as a space if its value is less-than or
		// equal-to `CHARACTER_SPACE`.
		//

		this.sync();

		for(int i=0;i<this.source.length;i+=1)
		{
			byte character;

			character=this.source[i];

			character=character<=CHARACTER_SPACE?CHARACTER_NULL:character;

			this.source[i]=character;
		}

		//
		// mark each word.
		// ---------------
		//

		// each bit therein corresponds to a byte in the source.
		// a bit of value 1 implies its corresponding byte is "marked".
		// the first 2 bytes of each word should be marked.
		byte[] word_marks=new byte[this.source.length];

		this.sync();

		for(int i=0;i<this.source.length-1;i+=1)
		{
			int     batch,marks;
			boolean is_word;

			// load 2 bytes as a single 16-bit value.
			//
			// this would be easier if i had C-like casts (or if i used a `ByteBuffer`
			// instead).
			batch =this.source[i+0]<<(0*8);
			batch|=this.source[i+1]<<(1*8);

			// check for the "NULL, non-NULL" pattern that indicates the start of a
			// word. 
			is_word =((batch>>(0*8))&0xFF)==CHARACTER_NULL;
			is_word&=((batch>>(1*8))&0xFF)!=CHARACTER_NULL;

			// if the pattern is recognized, mark the first two characters of the
			// word.
			marks=(is_word?0b11:0)<<((i+1)%8);

			// store marks.
			word_marks[(i+1)/8+0]|=(byte)((marks>>(0*8))&0xFF);
			word_marks[(i+1)/8+1]|=(byte)((marks>>(1*8))&0xFF);
		}

		//
		// replace the next byte of a single-letter word with a space.
		// -----------------------------------------------------------
		//
		// this is an important transformation to ensure single-letter words aren't
		// exempt in the final code. otherwise, the final code will have some words
		// that are of a single letter, which misaligns the proper 2-byte words.
		//

		this.sync();

		for(int i=0;i<this.source.length-3;i+=1)
		{
			int     batch;
			byte    marks;
			boolean is_single;

			// load a batch of 4 bytes as a 64-bit WORD.
			//
			// despite the last byte being extraneous, we still load all 4 bytes to
			// imitate C-like casting (for rewriting purposes).
			batch =this.source[i+0]<<(0*8);
			batch|=this.source[i+1]<<(1*8);
			batch|=this.source[i+2]<<(2*8);
			batch|=this.source[i+3]<<(3*8);

			// check if the batch contains a single-character word.
			//
			// similar to recognizing the start of words, the pattern that indicates
			// a single-letter word is "NULL or SPACE, none-NULL, then NULL".
			//
			// the first byte may either be NULL or SPACE because the source is
			// written back into with maybe a SPACE, which interfers with subsequent
			// advancements. otherwise, the NULL that formerly indicated the start of
			// a word would be replaced, thereby ignoring the word entirely.
			is_single =((batch>>(0*8))&0xFF)==CHARACTER_NULL;
			is_single|=((batch>>(0*8))&0xFF)==CHARACTER_SPACE;
			is_single&=((batch>>(1*8))&0xFF)!=CHARACTER_NULL;
			is_single&=((batch>>(2*8))&0xFF)==CHARACTER_NULL;

			// set the byte after the single-character to SPACE, if applicable.
			//
			// this transformation is only applicable if the byte that is to be
			// replaced is NULL; therefore, a simple OR is okay.
			batch|=is_single?(CHARACTER_SPACE<<(2*8)):0;

			// store.
			this.source[i+0]=(byte)(batch>>(0*8));
			this.source[i+1]=(byte)(batch>>(1*8));
			this.source[i+2]=(byte)(batch>>(2*8));
			this.source[i+3]=(byte)(batch>>(3*8));
		}

		//
		// exclude letters past the first two letters of each word.
		// --------------------------------------------------------
		//
		// because words are limited to 2 characters, the extra characters serve
		// no effect and, therefore, are excluded.
		//
		// this is easy to do since we previously marked the first 2 bytes of each
		// word, thereby allowing a simple check of a bit.
		//

		this.sync();

		for(int i=1;i<this.source.length;i+=1)
		{
			int character,marks;
			boolean mark;

			// load the mark, which is a single bit.
			marks=word_marks[i/8];
			mark =(marks&(1<<(i%8)))!=0;

			// load character.
			character=this.source[i];

			// nullify/exclude if marked.
			character=mark?character:CHARACTER_NULL;

			// store.
			this.source[i]=(byte)character;
		}

		//
		// sieve the NULL bytes to the back.
		// ---------------------------------
		//
		// currently, the source has extraneous NULL bytes mixed in the effective
		// code. we should sieve them out.
		//
		// we can do a simple sort between NULL and non-NULL characters, which will
		// move the NULL bytes towards the back.
		//

		{
			boolean sorted;

			// a simple odd-even sort.
			//
			// odd-even sort is portable to the GPU.

			do
			{
				boolean swap;
				byte a,b,c;

				sorted=true;

				this.sync();

				// sort odds.
				for(int i=1;i<this.source.length-1;i+=2)
				{
					// check if swappable.
					swap=(this.source[i]==CHARACTER_NULL?1:0)>(this.source[i+1]==CHARACTER_NULL?1:0);

					// swap, if swappable.
					a=this.source[i+0];
					b=this.source[i+1];
					this.source[i+(swap?1:0)]=a;
					this.source[i+(swap?0:1)]=b;

					// 
					sorted=swap?false:sorted;
				}

				// same thing for the following as the above, except its for evens.

				this.sync();

				// sort evens.
				for(int i=0;i<this.source.length-1;i+=2)
				{
					swap=(this.source[i]==CHARACTER_NULL?1:0)>(this.source[i+1]==CHARACTER_NULL?1:0);

					a=this.source[i+0];
					b=this.source[i+1];
					this.source[i+(swap?1:0)]=a;
					this.source[i+(swap?0:1)]=b;

					sorted=swap?false:sorted;
				}
			}
			while(!sorted);
		}

//!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!//
//                                                                            //
//          !!  THE SUBSEQUENT CODE IS BRANCHY AND GROSS AND BAD  !!          //
//                                                                            //
//!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!//

		//
		// inline each word.
		// -----------------
		//
		// for more information, visit the method `inline_words`.
		//

		this.sync();

		for(int i=0;i<this.source.length;)
		{
			// recursive. icky....
			i=this.inline_words(i);
		}

		//
		// execute the code.
		// -----------------
		//

		for(int i=0;i<this.word_count;i+=1)
		{
			short word;

			word=this.word_buffer[i];

			int a,b,v;

			switch(word)
			{
			case BUILTIN_WORD_ADD:
				{
					if(this.stack_mass<2)throw new Exception("stack underflow");
					a=this.stack[--this.stack_mass];
					b=this.stack[--this.stack_mass];
					v=a+b;
					this.stack[this.stack_mass++]=v;
					System.out.println(""+a+"+"+b+"="+v);
				}
				break;
			case BUILTIN_WORD_SUB:
				{
					if(this.stack_mass<2)throw new Exception("stack underflow");
					a=this.stack[--this.stack_mass];
					b=this.stack[--this.stack_mass];
					v=a-b;
					this.stack[this.stack_mass++]=v;
					System.out.println(""+a+"-"+b+"="+v);
				}
				break;
			case BUILTIN_WORD_MUL:
				{
					if(this.stack_mass<2)throw new Exception("stack underflow");
					a=this.stack[--this.stack_mass];
					b=this.stack[--this.stack_mass];
					v=a*b;
					this.stack[this.stack_mass++]=v;
					System.out.println(""+a+"*"+b+"="+v);
				}
				break;
			case BUILTIN_WORD_DIV:
				{
					if(this.stack_mass<2)throw new Exception("stack underflow");
					a=this.stack[--this.stack_mass];
					b=this.stack[--this.stack_mass];
					v=a/b;
					this.stack[this.stack_mass++]=v;
					System.out.println(""+a+"/"+b+"="+v);
				}
				break;
			default:
				{
					int h,l;

					// check if the word is numeric.
					boolean word_is_numeric;
					{
						// extract the characters.
						l=(word>>0)&0xFF;
						h=(word>>8)&0xFF;

						// check if the value of each character is within the bounds of a
						// valid numeric word.
						word_is_numeric =(l>='A'&&l<='F')||(l>='0'&&l<='9');
						word_is_numeric&=(h>='A'&&h<='F')||(h>='0'&&h<='9');
					}

					if(word_is_numeric)
					{
						boolean p;

						// convert from text into binary form.

						// higher 4 bits / most significant hex digit.
						h =(word>>0)&0xFF;
						// since the alphabetic characters are discontiguous from the
						// numeric characters, it must be checked for either set of
						// characters.
						p =h>='A'&&h<='Z';
						h-=p?'A':'0';
						h+=p?10:0;

						// lower 4 bits / least significant hex digit.
						l =(word>>8)&0xFF;
						p =l>='A'&&l<='Z';
						l-=p?'A':'0';
						l+=p?10:0;

						// combine.
						v=(h<<4)|(l&0xFF);

						// push onto the stack.
						this.stack[this.stack_mass++]=v;
					}
					else
					{
						// this branch shouldn't occur since we ignore undefined words in
						// previous stages of transformations.
						print_word(word);
						throw new Exception("wtf kinda word wtf");
					}
				}
				break;
			}
		}

		// finally.
		if(this.stack_mass<1)throw new Exception("stack is empty");
		System.out.println("result:"+this.stack[--this.stack_mass]);

		// the status should be set to an error code instead of the method throwing
		// an exception.
		return status;
	}

	// this is to imitate synchronization between threads for the rewriting to be
	// easier.
	private void sync()
	{
		// do nothing.
	}

	// just loads the 2 bytes at the `i`th index of `buffer` into a single 16-bit
	// value.
	private static short load_word(byte[] buffer, int i)
	{
		short word;

		word =(short)(buffer[i+0]<<(0*8)); // 1st character.
		word|=(short)(buffer[i+1]<<(1*8)); // 2nd character.

		return word;
	}

	// extract the two letters of the 16-bit `word`, then print them as a cohesive
	// word.
	private static void print_word(short word)
	{
		char a,b;

		a=(char)((word>>0)&0xFF); // 1st character.
		b=(char)((word>>8)&0xFF); // 2nd character.

		System.out.print(""+a+b+" ");
	}

	//
	//
	//
	private int inline_words(int offset)
	{
		boolean do_ret,do_skip_to_ret;

		do_ret=false;
		do_skip_to_ret=false;

		for(;offset<this.source.length;offset+=2)
		{
			short word;

			if(do_ret)break;

			word=load_word(this.source,offset);

			if(do_skip_to_ret&&word!=BUILTIN_WORD_RET)continue;

			switch(word)
			{
			case BUILTIN_WORD_DEF:
				{
					short new_word;

					new_word=load_word(this.source,offset+2);

					this.word_table[new_word]=offset+2+2;

					do_skip_to_ret=true;
				}
				break;
			case BUILTIN_WORD_RET:
				{
					do_ret=true;
				}
				break;
			default:
				{
					int word_offset;

					word_offset=this.word_table[word];

					if(word_offset==WORD_OFFSET_BUILTIN)
					{
						this.word_buffer[this.word_count]=word;
						this.word_count+=1;
					}
					else if(word_offset==WORD_OFFSET_UNDEFINED)
					{
						// IGNORE.
					}
					else
					{
						// RECURSE.

						this.inline_words(word_offset);
					}
				}
				break;
			}
		}

		return offset;
	}
}
