/*
Inference for Llama-2 Transformer model in pure Java.

Example compile: (see README for more details)
$ javac Llama2.java

Then run with:
$ java Llama2 stories15M.bin
*/
// ----------------------------------------------------------------------------
// Transformer and RunState structs, and related memory management

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentScope;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

final class Config {
    final int dim; // transformer dimension
    final int hidden_dim; // for ffn layers
    final int n_layers; // number of layers
    final int n_heads; // number of query heads
    final int n_kv_heads; // number of key/value heads (can be < query heads because of multiquery)
    final int vocab_size; // vocabulary size, usually 256 (byte-level)
    final int seq_len; // max sequence length
    final boolean shared_weights;
    final int head_size;

    Config(ByteBuffer buffer) {
        this.dim = buffer.getInt();
        this.hidden_dim = buffer.getInt();
        this.n_layers = buffer.getInt();
        this.n_heads = buffer.getInt();
        this.n_kv_heads = buffer.getInt();
        int vocab_size = buffer.getInt();
        this.vocab_size = Math.abs(vocab_size);
        this.seq_len = buffer.getInt();
        this.shared_weights = vocab_size > 0;
        this.head_size = dim / n_heads;
    }

    @Override
    public String toString() {
        return "Config{" +
                "dim=" + dim +
                ", hidden_dim=" + hidden_dim +
                ", n_layers=" + n_layers +
                ", n_heads=" + n_heads +
                ", n_kv_heads=" + n_kv_heads +
                ", vocab_size=" + vocab_size +
                ", seq_len=" + seq_len +
                ", shared_weights=" + shared_weights +
                ", head_size=" + head_size +
                '}';
    }
}

final class Weights {
    // token embedding table
    final FloatBuffer token_embedding_table; // (vocab_size, dim)
    // weights for rmsnorms
    final FloatBuffer[] rms_att_weight; // (layer, dim) rmsnorm weights
    // weights for matmuls
    final FloatBuffer[] wq; // (layer, dim, dim)
    final FloatBuffer[] wk; // (layer, dim, dim)
    final FloatBuffer[] wv; // (layer, dim, dim)
    final FloatBuffer[] wo; // (layer, dim, dim)
    final FloatBuffer[] rms_ffn_weight; // (layer, dim)
    // weights for ffn
    final FloatBuffer[] w1; // (layer, hidden_dim, dim)
    final FloatBuffer[] w2; // (layer, dim, hidden_dim)
    final FloatBuffer[] w3; // (layer, hidden_dim, dim)
    // final rmsnorm
    final FloatBuffer rms_final_weight; // (dim,)
    // freq_cis for RoPE relatively positional embeddings
    final FloatBuffer freq_cis_real; // (seq_len, head_size/2)
    final FloatBuffer freq_cis_imag; // (seq_len, head_size/2)
    // (optional) classifier weights for the logits, on the last layer
    final FloatBuffer wcls; // (vocab_size, dim)

    static FloatBuffer takeFloats(MemorySegment memorySegment, long[] position, int... dims) {
        long totalBytes = 1;
        for (int d : dims) {
            totalBytes *= d;
        }
        totalBytes *= Float.BYTES;
        MemorySegment slice = memorySegment.asSlice(position[0], totalBytes);
        position[0] += totalBytes;
        return slice.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }

    static FloatBuffer[] takeArray(MemorySegment memorySegment, long[] position, int dim0, int... dims) {
        FloatBuffer[] segments = new FloatBuffer[dim0];
        for (int i = 0; i < dim0; ++i) {
            segments[i] = takeFloats(memorySegment, position, dims);
        }
        return segments;
    }

// ----------------------------------------------------------------------------
// initialization: read from checkpoint

    Weights(Config config, MemorySegment memorySegment) {
        long[] position = new long[]{0};
        this.token_embedding_table = takeFloats(memorySegment, position, config.vocab_size, config.dim);
        this.rms_att_weight = takeArray(memorySegment, position, config.n_layers, config.dim);
        this.wq = takeArray(memorySegment, position, config.n_layers, config.dim, config.dim);
        this.wk = takeArray(memorySegment, position, config.n_layers, config.dim, config.dim);
        this.wv = takeArray(memorySegment, position, config.n_layers, config.dim, config.dim);
        this.wo = takeArray(memorySegment, position, config.n_layers, config.dim, config.dim);
        this.rms_ffn_weight = takeArray(memorySegment, position, config.n_layers, config.dim);
        this.w1 = takeArray(memorySegment, position, config.n_layers, config.hidden_dim, config.dim);
        this.w2 = takeArray(memorySegment, position, config.n_layers, config.dim, config.hidden_dim);
        this.w3 = takeArray(memorySegment, position, config.n_layers, config.hidden_dim, config.dim);
        this.rms_final_weight = takeFloats(memorySegment, position, config.dim);
        this.freq_cis_real = takeFloats(memorySegment, position, config.seq_len, config.head_size / 2);
        this.freq_cis_imag = takeFloats(memorySegment, position, config.seq_len, config.head_size / 2);
        this.wcls = config.shared_weights
                ? this.token_embedding_table
                : takeFloats(memorySegment, position, config.vocab_size, config.dim);
    }
}

final class RunState {
    // current wave of activations
    final float[] x; // activation at current time stamp (dim,)
    final float[] xb; // same, but inside a residual branch (dim,)
    final float[] xb2; // an additional buffer just for convenience (dim,)
    final float[] hb; // buffer for hidden dimension in the ffn (hidden_dim,)
    final float[] hb2; // buffer for hidden dimension in the ffn (hidden_dim,)
    final float[] q; // query (dim,)
    final float[] k; // key (dim,)
    final float[] v; // value (dim,)
    final float[] att; // buffer for scores/attention values (n_heads, seq_len)
    final float[] logits; // output logits
    // kv cache
    final float[] key_cache;   // (layer, seq_len, dim)
    final float[] value_cache; // (layer, seq_len, dim)
    final int[] indices; // (vocab_size)

    RunState(Config config) {
        this.x = new float[config.dim];
        this.xb = new float[config.dim];
        this.xb2 = new float[config.dim];
        this.hb = new float[config.hidden_dim];
        this.hb2 = new float[config.hidden_dim];
        this.q = new float[config.dim];
        this.k = new float[config.dim];
        this.v = new float[config.dim];
        this.att = new float[config.n_heads * config.seq_len];
        this.logits = new float[config.vocab_size];
        this.key_cache = new float[config.n_layers * config.seq_len * config.dim];
        this.value_cache = new float[config.n_layers * config.seq_len * config.dim];
        this.indices = IntStream.range(0, config.vocab_size).toArray();
    }
}

class Llama2 {

// ----------------------------------------------------------------------------
// neural net blocks

    static void accum(float[] a, float[] b, int size) {
        for (int i = 0; i < size; i++) {
            a[i] += b[i];
        }
    }

    static void rmsnorm(float[] o, float[] x, FloatBuffer weight, int size) {
        // calculate sum of squares
        float ss = 0.0f;
        for (int j = 0; j < size; j++) {
            ss += x[j] * x[j];
        }
        ss /= size;
        ss += 1e-5f;
        ss = 1.0f / (float) Math.sqrt(ss);
        // normalize and scale
        for (int j = 0; j < size; j++) {
            o[j] = weight.get(j) * (ss * x[j]);
        }
    }

    static void softmax(float[] x, int xOffset, int size) {
        // find max value (for numerical stability)
        float max_val = x[0 + xOffset];
        for (int i = 1; i < size; i++) {
            if (x[i + xOffset] > max_val) {
                max_val = x[i + xOffset];
            }
        }
        // exp and sum
        float sum = 0.0f;
        for (int i = 0; i < size; i++) {
            x[i + xOffset] = (float) Math.exp(x[i + xOffset] - max_val);
            sum += x[i + xOffset];
        }
        // normalize
        for (int i = 0; i < size; i++) {
            x[i + xOffset] /= sum;
        }
    }

    static void matmul(float[] xout, float[] x, FloatBuffer w, int n, int d) {
        // W (d,n) @ x (n,) -> xout (d,)
        // by far the most amount of time is spent inside this little function
        IntStream.range(0, d).parallel().forEach(i -> {
            float val = 0.0f;
            for (int j = 0; j < n; j++) {
                val += w.get(i * n + j) * x[j];
            }
            xout[i] = val;
        });
    }

    static void transformer(int token, int pos, Config p, RunState s, Weights w) {

        // a few convenience variables
        int dim = p.dim;
        int hidden_dim = p.hidden_dim;
        int head_size = p.head_size;

        // copy the token embedding into x
        w.token_embedding_table.get(token * dim, s.x, 0, dim);

        // forward all the layers
        for (int l = 0; l < p.n_layers; l++) {

            // attention rmsnorm
            rmsnorm(s.xb, s.x, w.rms_att_weight[l], dim);

            // qkv matmuls for this position
            matmul(s.q, s.xb, w.wq[l], dim, dim);
            matmul(s.k, s.xb, w.wk[l], dim, dim);
            matmul(s.v, s.xb, w.wv[l], dim, dim);

            // RoPE relative positional encoding: complex-valued rotate q and k by freq_cis in each head
            for (int i = 0; i < dim; i += 2) {
                float q0 = s.q[i];
                float q1 = s.q[i+1];
                float k0 = s.k[i];
                float k1 = s.k[i+1];
                float fcr = w.freq_cis_real.get(pos * head_size / 2 + (i % head_size) / 2);
                float fci = w.freq_cis_imag.get(pos * head_size / 2 + (i % head_size) / 2);
                s.q[i]   = q0 * fcr - q1 * fci;
                s.q[i+1] = q0 * fci + q1 * fcr;
                s.k[i]   = k0 * fcr - k1 * fci;
                s.k[i+1] = k0 * fci + k1 * fcr;
            }

            // save key,value at this time step (pos) to our kv cache
            int loff = l * p.seq_len * dim; // kv cache layer offset for convenience

            System.arraycopy(s.k, 0, s.key_cache, loff + pos * dim, dim);
            System.arraycopy(s.v, 0, s.value_cache, loff + pos * dim, dim);

            // multihead attention. iterate over all heads
            IntStream.range(0, p.n_heads).parallel().forEach(h -> {
                // get the query vector for this head
                // float* q = s.q + h * head_size;
                int qOffset = h * head_size;

                // attention scores for this head
                // float* att = s.att + h * p.seq_len;
                int attOffset = h * p.seq_len;

                // iterate over all timesteps, including the current one
                for (int t = 0; t <= pos; t++) {
                    // get the key vector for this head and at this timestep
                    // float* k = s.key_cache + loff + t * dim + h * head_size;
                    int keyCacheOffset = loff + t * dim + h * head_size;
                    // calculate the attention score as the dot product of q and k
                    float score = 0.0f;
                    for (int i = 0; i < head_size; i++) {
                        score += s.q[qOffset + i] * s.key_cache[keyCacheOffset + i];
                    }
                    score /= (float) Math.sqrt(head_size);
                    // save the score to the attention buffer
                    s.att[attOffset + t] = score;
                }

                // softmax the scores to get attention weights, from 0..pos inclusively
                softmax(s.att, attOffset, pos + 1);

                // weighted sum of the values, store back into xb
                // float* xb = s.xb + h * head_size;
                int xbOffset = h * head_size;
                // memset(xb, 0, head_size * sizeof(float));
                Arrays.fill(s.xb, xbOffset, xbOffset + head_size, 0f);

                for (int t = 0; t <= pos; t++) {
                    // get the value vector for this head and at this timestep
                    // float* v = s.value_cache + loff + t * dim + h * head_size;
                    int vOffset = loff + t * dim + h * head_size;
                    // get the attention weight for this timestep
                    float a = s.att[attOffset + t];
                    // accumulate the weighted value inconfigto xb
                    for (int i = 0; i < head_size; i++) {
                        s.xb[xbOffset + i] += a * s.value_cache[vOffset + i];
                    }
                }
            });

            // final matmul to get the output of the attention
            matmul(s.xb2, s.xb, w.wo[l], dim, dim);

            // residual connection back into x
            accum(s.x, s.xb2, dim);

            // ffn rmsnorm
            rmsnorm(s.xb, s.x, w.rms_ffn_weight[l], dim);

            // Now for FFN in PyTorch we have: self.w2(F.silu(self.w1(x)) * self.w3(x))
            // first calculate self.w1(x) and self.w3(x)
            matmul(s.hb, s.xb, w.w1[l], dim, p.hidden_dim);
            matmul(s.hb2, s.xb, w.w3[l], dim, p.hidden_dim);

            // F.silu; silu(x)=x*σ(x),where σ(x) is the logistic sigmoid
            for (int i = 0; i < hidden_dim; i++) {
                s.hb[i] = s.hb[i] / (1.0f + (float) Math.exp(-s.hb[i]));
            }

            // elementwise multiply with w3(x)
            for (int i = 0; i < hidden_dim; i++) {
                s.hb[i] = s.hb[i] * s.hb2[i];
            }

            // final matmul to get the output of the ffn
            matmul(s.xb, s.hb, w.w2[l], p.hidden_dim, dim);

            // residual connection
            accum(s.x, s.xb, dim);
        }

        // final rmsnorm
        rmsnorm(s.x, s.x, w.rms_final_weight, dim);

        // classifier into logits
        matmul(s.logits, s.x, w.wcls, dim, p.vocab_size);
    }

// ----------------------------------------------------------------------------
// byte pair encoding (BPE) tokenizer, encodes strings into tokens so we can prompt

    static int str_lookup(String str, String[] vocab, int vocab_size) {
        // find the first perfect match for str in vocab, return its index or -1 if not found
        for (int i = 0; i < vocab_size; i++) {
            if (str.equals(vocab[i])) {
                return i;
            }
        }
        return -1;
    }

    static int bpe_encode(String text, String[] vocab, float[] vocab_scores, int vocab_size, int[] tokens) {
        // first encode every individual byte in the input string
        int n_tokens = 0; // the number of tokens
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            String singleChar = String.valueOf(c);
            int id = str_lookup(singleChar, vocab, vocab_size);
            if (id == -1) {
                System.err.printf("not good\n");
                System.exit(1);
            }
            tokens[n_tokens] = id;
            n_tokens++;
        }

        // merge the best consecutive pair each iteration, according the scores in vocab_scores
        while (true) {
            float best_score = -1e10f;
            int best_id = -1;
            int best_idx = -1;

            for (int i = 0; i < n_tokens - 1; ++i) {
                // check if we can merge the pair (tokens[i], tokens[i+1])
                String str_buffer = vocab[tokens[i]] + vocab[tokens[i + 1]];
                int id = str_lookup(str_buffer, vocab, vocab_size);
                if (id != -1 && vocab_scores[id] > best_score) {
                    // this merge pair exists in vocab! record its score and position
                    best_score = vocab_scores[id];
                    best_id = id;
                    best_idx = i;
                }
            }

            if (best_idx == -1) {
                break; // we couldn't find any more pairs to merge, so we're done
            }

            // merge the consecutive pair (best_idx, best_idx+1) into new token best_id
            tokens[best_idx] = best_id;
            // delete token at position best_idx+1, shift the entire sequence back 1
            for (int i = best_idx + 1; i < n_tokens - 1; i++) {
                tokens[i] = tokens[i + 1];
            }
            n_tokens--; // token length decreased
        }

        return n_tokens;
    }

// ----------------------------------------------------------------------------
// utilities: time / rng

    static long time_in_ms() {
        // return time in milliseconds, for benchmarking the model speed
        return System.nanoTime() / 1_000_000;
    }

    static long rng_seed;

    static int random_u32() {
        // xorshift rng: https://en.wikipedia.org/wiki/Xorshift#xorshift.2A
        rng_seed ^= rng_seed >> 12;
        rng_seed ^= rng_seed << 25;
        rng_seed ^= rng_seed >> 27;
        return (int) ((rng_seed * 0x2545F4914F6CDD1DL) >> 32);
    }

    static float random_f32() { // random float32 in [0,1)
        return (random_u32() >>> 8) / 16777216.0f;
    }

// ----------------------------------------------------------------------------
// sampling can be done in a few ways: greedy argmax, sampling, top-p sampling

    static int argmax(float[] probabilities, int n) {
        // return the index that has the highest probability
        int max_i = 0;
        float max_p = probabilities[0];
        for (int i = 1; i < n; i++) {
            if (probabilities[i] > max_p) {
                max_i = i;
                max_p = probabilities[i];
            }
        }
        return max_i;
    }

    static int sample(float[] probabilities, int n) {
        // sample index from probabilities (they must sum to 1!)
        float r = random_f32();
        float cdf = 0.0f;
        for (int i = 0; i < n; i++) {
            cdf += probabilities[i];
            if (r < cdf) {
                return i;
            }
        }
        return n - 1; // in case of rounding errors
    }

    static void swap(int[] array, int from, int to) {
        int tmp = array[from];
        array[from] = array[to];
        array[to] = tmp;
    }

    static void siftDown(int[] array, int from, int n, Comparator<Integer> comparator) {
        int prev = from, next;
        while ((next = 2 * prev + 1) < n) {
            int r = 2 * prev + 2;
            if (r < n && comparator.compare(array[r], array[next]) < 0) {
                next = r;
            }
            if (comparator.compare(array[next], array[prev]) < 0) {
                swap(array, prev, next);
                prev = next;
            } else {
                break;
            }
        }
    }

    static int sample_topp(float[] probabilities, float topp, int[] indices) {
        // top-p sampling (or "nucleus sampling") samples from the smallest set of
        // tokens that exceed probability topp. This way we never sample tokens that
        // have very low probabilities and are less likely to go "off the rails".
        Comparator<Integer> comparator = Comparator.<Integer>comparingDouble(i -> probabilities[i]).reversed();

        final int n = indices.length;

        // Common case: If the largest probability > topp, skip the partial sorting.
        int maxIndex = 0;
        for (int i = 1; i < n; ++i) {
            if (probabilities[i] > probabilities[maxIndex]) {
                maxIndex = i;
            }
        }
        if (probabilities[maxIndex] > topp) {
            return maxIndex;
        }

        // build heap O(n)
        for (int i = n / 2 - 1; i >= 0; --i) {
            siftDown(indices, i, n, comparator);
        }

        // truncate the list where cumulative probability exceeds topp O(k log n)
        // largest elements are the last k
        float cumulative_prob = 0.0f;
        int last_idx = indices.length - 1;
        for (int i = indices.length - 1; i > 0; i--) {
            swap(indices, 0, i);
            cumulative_prob += probabilities[indices[i]];
            if (cumulative_prob > topp) {
                last_idx = i;
                break; // we've exceeded topp by including last_idx
            }
            siftDown(indices, 0, i - 1, comparator);
        }

        // sample from the truncated list
        float r = random_f32() * cumulative_prob;
        float cdf = 0.0f;
        for (int i = indices.length - 1; i >= last_idx; i--) {
            cdf += probabilities[indices[i]];
            if (r < cdf) {
                return indices[i];
            }
        }

        return indices[last_idx]; // in case of rounding errors
    }

// ----------------------------------------------------------------------------
// int main

    static void error_usage() {
        System.err.println("Usage:   java Llama2 <checkpoint> [options]");
        System.err.println("Example: java Lamma2 model.bin -n 256 -i \"Once upon a time\"");
        System.err.println("Options:");
        System.err.println("  -t <float>  temperature, default 1.0");
        System.err.println("  -p <float>  p value in top-p (nucleus) sampling. default 0.9, 0 = off");
        System.err.println("  -s <int>    random seed, default time(NULL)");
        System.err.println("  -n <int>    number of steps to run for, default 256. 0 = max_seq_len");
        System.err.println("  -i <string> input prompt\n");
        System.exit(1);
    }

    public static void main(String[] args) throws IOException {

        // default inits
        String checkpoint = null; // e.g. out/model.bin
        float temperature = 1.0f; // 0.0 = greedy deterministic. 1.0 = original. don't set higher
        float topp = 0.9f;        // top-p in nucleus sampling
        rng_seed = System.currentTimeMillis() / 1000; // (unsigned int)time(NULL);
        int steps = 256;          // max number of steps to run for, 0: use seq_len
        String prompt = null;     // prompt string

        // poor man's C argparse so we can override the defaults above from the command line
        if (args.length >= 1) {
            checkpoint = args[0];
        } else {
            error_usage();
        }
        for (int i = 1; i < args.length; i += 2) {
            // do some basic validation
            if (i + 1 >= args.length) { error_usage(); } // must have arg after flag
            if (args[i].charAt(0) != '-') { error_usage(); } // must start with dash
            if (args[i].length() != 2) { error_usage(); } // must be -x (one dash, one letter)
            // read in the args
            switch (args[i].charAt(1)) {
                case 't' -> temperature = Float.parseFloat(args[i + 1]);
                case 'p' -> topp = Float.parseFloat(args[i + 1]);
                case 's' -> rng_seed = Integer.parseInt(args[i + 1]);
                case 'n' -> steps = Integer.parseInt(args[i + 1]);
                case 'i' -> prompt = args[i + 1];
                default -> error_usage();
            }
        }

        if (rng_seed == 0) {
            System.err.println("Cannot use seed=0 because of the rng alg used");
            System.exit(1);
        }

        FileChannel fileChannel = FileChannel.open(Paths.get(checkpoint), StandardOpenOption.READ);
        long size = fileChannel.size();
        MemorySegment mappedFile = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, size, SegmentScope.global());
        int configSize = 7 * Integer.BYTES;
        // read in the config header
        ByteBuffer configBuffer = mappedFile.asSlice(0, configSize).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        Config config = new Config(configBuffer);
        System.err.println(config);
        Weights weights = new Weights(config, mappedFile.asSlice(configSize));

        // right now we cannot run for more than config.seq_len steps
        if (steps <= 0 || steps > config.seq_len) {
            steps = config.seq_len;
        }

        // read in the tokenizer.bin file
        String[] vocab = new String[config.vocab_size];
        float[] vocab_scores = new float[config.vocab_size];
        try (FileChannel channel = FileChannel.open(Paths.get("tokenizer.bin"), StandardOpenOption.READ)) {
            ByteBuffer tokBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            tokBuffer.order(ByteOrder.LITTLE_ENDIAN);
            int max_token_length = tokBuffer.getInt();
            for (int i = 0; i < config.vocab_size; i++) {
                vocab_scores[i] = tokBuffer.getFloat();
                int len = tokBuffer.getInt();
                byte[] bytes = new byte[len];
                tokBuffer.get(bytes);
                vocab[i] = new String(bytes);
            }
        }

        // create and init the application RunState
        RunState state = new RunState(config);

        // process the prompt, if any
        int[] prompt_tokens = null;
        int num_prompt_tokens = 0;
        if (prompt != null) {
            prompt_tokens = new int[config.seq_len];
            num_prompt_tokens = bpe_encode(prompt, vocab, vocab_scores, config.vocab_size, prompt_tokens);
        }

        // start the main loop
        long start = 0;  // used to time our code, only initialized after first iteration
        int next;        // will store the next token in the sequence
        int token = 1;   // init with token 1 (=BOS), as done in Llama-2 sentencepiece tokenizer
        int pos = 0;     // position in the sequence
        while (pos < steps) {

            // forward the transformer to get logits for the next token
            transformer(token, pos, config, state, weights);

            // advance the state state machine
            if (pos < num_prompt_tokens) {
                // if we are still processing the input prompt, force the next prompt token
                next = prompt_tokens[pos];
            } else {
                // sample the next token
                if (temperature == 0.0f) {
                    // greedy argmax sampling: take the token with the highest probability
                    next = argmax(state.logits, config.vocab_size);
                } else {
                    // apply the temperature to the logits
                    for (int q = 0; q < config.vocab_size; q++) {
                        state.logits[q] /= temperature;
                    }
                    // apply softmax to the logits to get the probabilities for next token
                    softmax(state.logits, 0, config.vocab_size);
                    // we sample from this distribution to get the next token
                    if (topp <= 0) {
                        // simply sample from the predicted probability distribution
                        next = sample(state.logits, config.vocab_size);
                    } else {
                        // top-p (nucleus) sampling, clamping the least likely tokens to zero
                        next = sample_topp(state.logits, topp, state.indices);
                    }
                }
            }
            pos++;

            // data-dependent terminating condition: the BOS (1) token delimits sequences
            if (next == 1) {
                break;
            }

            // following BOS (1) token, sentencepiece decoder strips any leading whitespace (see PR#89)
            String token_str = (token == 1 && vocab[next].charAt(0) == ' ') ? vocab[next].substring(1) : vocab[next];
            System.out.print(token_str);
            System.out.flush();
            token = next;

            // init the timer here because the first iteration can be slower
            if (start == 0) {
                start = time_in_ms();
            }
        }

        System.out.println();

        // report achieved tok/s (pos-1 because the timer starts after first iteration)
        if (pos > 1) {
            long end = time_in_ms();
            System.err.printf("\nachieved tok/s: %f\n", (pos - 1) / (double) (end - start) * 1000);
        }
    }
}
