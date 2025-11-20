FLUX: DATA-PARALLEL FORTH-LIKE INTERPRETED PROGRAMMING LANGUAGE



FLUX is a data-parallel constant-time Forth-like engine for scripting.

A Flux program is invoked per element of some buffer and each invocation
effectively executes in parallel, similar to kernels in GPU programming.

Branchs are prohibited; all words are inlined, effectively reducing the entire
program into elementary words.

The final words are schedulad to maintain constant-time execution.

This code is written such that it can be ported to execute in the GPU, via GLSL.



This is literally just like how processors break instructions into micro-ops (my elementary words),
then schedule them to execution units (stages) and cycles (ticks).


Forth script -> elementary words -> scheduled words

