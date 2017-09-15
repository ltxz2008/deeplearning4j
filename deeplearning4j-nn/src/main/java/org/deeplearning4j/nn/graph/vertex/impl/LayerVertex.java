/*-
 *
 *  * Copyright 2016 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.graph.vertex.impl;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.api.layers.IOutputLayer;
import org.deeplearning4j.nn.api.layers.RecurrentLayer;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.BaseGraphVertex;
import org.deeplearning4j.nn.graph.vertex.VertexIndices;
import org.deeplearning4j.nn.layers.BaseOutputLayer;
import org.deeplearning4j.nn.layers.FrozenLayer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.primitives.Pair;

import java.util.Arrays;

/**
 * LayerVertex is a GraphVertex with a neural network Layer (and, optionally an {@link InputPreProcessor}) in it
 *
 * @author Alex Black
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LayerVertex extends BaseGraphVertex {

    private Layer layer;
    private final InputPreProcessor layerPreProcessor;

    /**
     * Create a network input vertex:
     */
    public LayerVertex(ComputationGraph graph, String name, int vertexIndex, Layer layer,
                    InputPreProcessor layerPreProcessor, boolean outputVertex) {
        this(graph, name, vertexIndex, null, null, layer, layerPreProcessor, outputVertex);
    }

    public LayerVertex(ComputationGraph graph, String name, int vertexIndex, VertexIndices[] inputVertices,
                    VertexIndices[] outputVertices, Layer layer, InputPreProcessor layerPreProcessor,
                    boolean outputVertex) {
        super(graph, name, vertexIndex, inputVertices, outputVertices);
        this.graph = graph;
        this.vertexName = name;
        this.vertexIndex = vertexIndex;
        this.inputVertices = inputVertices;
        this.outputVertices = outputVertices;
        this.layer = layer;
        this.layerPreProcessor = layerPreProcessor;
        this.outputVertex = outputVertex;

        this.inputs = new INDArray[(inputVertices != null ? inputVertices.length : 0)];
    }

    public void setLayerAsFrozen() {
        if (this.layer instanceof FrozenLayer)
            return;

        this.layer = new FrozenLayer(this.layer);
        this.layer.conf().getLayer().setLayerName(vertexName);
    }

    @Override
    public boolean isOutputVertex() {
        return outputVertex || layer instanceof BaseOutputLayer;
    }

    @Override
    public Activations activate(boolean training) {
        if (!canDoForward())
            throw new IllegalStateException("Cannot do forward pass: all inputs not set");

        return layer.activate(training);
    }

    @Override
    public Pair<Gradient, INDArray[]> doBackward(boolean tbptt) {
        if (!canDoBackward()) {
            throw new IllegalStateException("Cannot do backward pass: all epsilons not set. Layer " + vertexName
                            + " (idx " + vertexIndex + ") numInputs " + numInputs() );  // + "; numOutputs "
//                            + getNumOutputConnections());
        }

        Gradients pair;
        if (tbptt && layer instanceof RecurrentLayer) {
            //Truncated BPTT for recurrent layers
            pair = ((RecurrentLayer) layer).tbpttBackpropGradient(GradientsFactory.getInstance().create(epsilon),
                            graph.getConfiguration().getTbpttBackLength());
        } else {
            //Normal backprop
            pair = layer.backpropGradient(GradientsFactory.getInstance().create(epsilon, null)); //epsTotal may be null for OutputLayers
        }

        if (layerPreProcessor != null) {
            INDArray eps = pair.get(0);
            eps = layerPreProcessor.backprop(eps, graph.batchSize());
            pair.set(0, eps);
        }

        //Layers always have single activations input -> always have single epsilon output during backprop
        return new Pair<>(pair.getParameterGradients(), pair.getActivationGradAsArray());
    }

    @Override
    public void setInput(int inputNumber, INDArray input) {
        inputs[inputNumber] = input;

        INDArray currInput = inputs[0];
        if (layerPreProcessor != null) {
            currInput = layerPreProcessor.preProcess(currInput, graph.batchSize());
        }
        layer.setInput(inputNumber, currInput);
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        layer.setBackpropGradientsViewArray(backpropGradientsViewArray);
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState,
                    int minibatchSize) {
        if (maskArrays == null || maskArrays.length == 0) {
            return new Pair<>(null, currentMaskState);
        }

        if (layerPreProcessor != null) {
            Pair<INDArray, MaskState> pair =
                            layerPreProcessor.feedForwardMaskArray(maskArrays[0], currentMaskState, minibatchSize);
            if (pair == null) {
                maskArrays[0] = null;
                currentMaskState = null;
            } else {
                maskArrays[0] = pair.getFirst();
                currentMaskState = pair.getSecond();
            }
        }

//        return layer.feedForwardMaskArray(maskArrays[0], currentMaskState, minibatchSize);
        throw new UnsupportedOperationException();
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LayerVertex(id=").append(vertexIndex).append(",name=\"").append(vertexName).append("\",inputs=")
                        .append(Arrays.toString(inputVertices)).append(",outputs=")
                        .append(Arrays.toString(outputVertices)).append(")");
        return sb.toString();
    }

    @Override
    public boolean canDoBackward() {
        if (!isOutputVertex()) {
            //inputs to frozen layer go unchecked, so could be null
            if (getLayer() instanceof FrozenLayer) {
                return true;
            } else {
                return super.canDoBackward();
            }
        }

        for (INDArray input : inputs) {
            if (input == null) {
                return false;
            }
        }

        if (!(layer instanceof IOutputLayer)) {
            if (epsilon == null) {
                return false;
            }
        }

        return true;
    }
}
