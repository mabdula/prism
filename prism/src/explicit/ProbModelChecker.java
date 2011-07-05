//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
//	
//------------------------------------------------------------------------------
//	
//	This file is part of PRISM.
//	
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//	
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//	
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//	
//==============================================================================

package explicit;

import java.util.BitSet;
import java.util.List;

import explicit.rewards.*;
import parser.State;
import parser.ast.*;
import prism.*;

/**
 * Super class for explicit-state probabilistic model checkers
 */
public class ProbModelChecker extends StateModelChecker
{
	// Model checking functions

	@Override
	public Object checkExpression(Model model, Expression expr) throws PrismException
	{
		Object res;

		// P operator
		if (expr instanceof ExpressionProb) {
			res = checkExpressionProb(model, (ExpressionProb) expr);
		}
		// R operator
		else if (expr instanceof ExpressionReward) {
			res = checkExpressionReward(model, (ExpressionReward) expr);
		}
		// S operator
		else if (expr instanceof ExpressionSS) {
			throw new PrismException("Explicit engine does not yet handle the S operator");
		}
		// Otherwise, use the superclass
		else {
			res = super.checkExpression(model, expr);
		}

		return res;
	}

	/**
	 * Model check a P operator expression and return the values for all states.
	 */
	protected StateValues checkExpressionProb(Model model, ExpressionProb expr) throws PrismException
	{
		String relOp; // Relational operator
		boolean min = false; // For nondeterministic models, are we finding min (true) or max (false) probs
		ModelType modelType = model.getModelType();

		StateValues probs = null;

		// Get info from prob operator
		relOp = expr.getRelOp();

		// Check for unhandled cases
		if (expr.getProb() != null)
			throw new PrismException("Explicit engine does not yet handle bounded P operators");

		// For nondeterministic models, determine whether min or max probabilities needed
		if (modelType.nondeterministic()) {
			if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
				// min
				min = true;
			} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
				// max
				min = false;
			} else {
				throw new PrismException("Can't use \"P=?\" for nondeterministic models; use \"Pmin=?\" or \"Pmax=?\"");
			}
		}

		// Compute probabilities
		switch (modelType) {
		case CTMC:
			probs = ((CTMCModelChecker) this).checkProbPathFormula(model, expr.getExpression());
			break;
		case CTMDP:
			probs = ((CTMDPModelChecker) this).checkProbPathFormula(model, expr.getExpression(), min);
			break;
		case DTMC:
			probs = ((DTMCModelChecker) this).checkProbPathFormula(model, expr.getExpression());
			break;
		case MDP:
			probs = ((MDPModelChecker) this).checkProbPathFormula(model, expr.getExpression(), min);
			break;
		/*case STPG:
			probs = ((STPGModelChecker) this).checkProbPathFormula(model, expr.getExpression(), min);
			break;*/
		default:
			throw new PrismException("Cannot model check " + expr + " for a " + modelType);
		}

		// Print out probabilities
		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(probs);
		}

		// For =? properties, just return values
		return probs;
	}
	
	/**
	 * Model check an R operator expression and return the values for all states.
	 */
	protected StateValues checkExpressionReward(Model model, ExpressionReward expr) throws PrismException
	{
		Object rs; // Reward struct index
		RewardStruct rewStruct = null; // Reward struct object
		Expression rb; // Reward bound (expression)
		double r = 0; // Reward bound (actual value)
		String relOp; // Relational operator
		Expression expr2; // expression
		boolean min = false; // For nondeterministic models, are we finding min (true) or max (false) rewards
		ModelType modelType = model.getModelType();
		StateValues rews = null;
		MCRewards modelRewards = null;
		int i;

		// Get info from reward operator
		rs = expr.getRewardStructIndex();
		relOp = expr.getRelOp();
		rb = expr.getReward();
		if (rb != null) {
			r = rb.evaluateDouble(constantValues, null);
			if (r < 0)
				throw new PrismException("Invalid reward bound " + r + " in R[] formula");
		}

		// Check for unhandled cases
		if (expr.getReward() != null)
			throw new PrismException("Explicit engine does not yet handle bounded R operators");
		// More? TODO

		// For nondeterministic models, determine whether min or max rewards needed
		if (modelType.nondeterministic()) {
			if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
				// min
				min = true;
			} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
				// max
				min = false;
			} else {
				throw new PrismException("Can't use \"R=?\" for nondeterministic models; use \"Rmin=?\" or \"Rmax=?\"");
			}
		}

		// Get reward info
		if (modulesFile == null)
			throw new PrismException("No model file to obtain reward structures");
		if (modulesFile.getNumRewardStructs() == 0)
			throw new PrismException("Model has no rewards specified");
		if (rs == null) {
			rewStruct = modulesFile.getRewardStruct(0);
		} else if (rs instanceof Expression) {
			i = ((Expression) rs).evaluateInt(constantValues, null);
			rs = new Integer(i); // for better error reporting below
			rewStruct = modulesFile.getRewardStruct(i - 1);
		} else if (rs instanceof String) {
			rewStruct = modulesFile.getRewardStructByName((String) rs);
		}
		if (rewStruct == null)
			throw new PrismException("Invalid reward structure index \"" + rs + "\"");

		// Build rewards (just MCs for now)
		switch (modelType) {
		case CTMC:
		case DTMC:
			modelRewards = buildMCRewardStructure(model, rewStruct);
			break;
		default:
			throw new PrismException("Cannot build rewards for " + modelType + "s");
		}
		
		// Compute rewards
		mainLog.println("Building reward structure...");
		switch (modelType) {
		case CTMC:
			rews = ((CTMCModelChecker) this).checkRewardFormula(model, modelRewards, expr.getExpression());
			break;
		case DTMC:
			rews = ((DTMCModelChecker) this).checkRewardFormula(model, modelRewards, expr.getExpression());
			break;
		case MDP:
			rews = ((MDPModelChecker) this).checkRewardFormula(model, expr.getExpression(), min);
			break;
		default:
			throw new PrismException("Cannot model check " + expr + " for " + modelType + "s");
		}

		// Print out probabilities
		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(rews);
		}

		// For =? properties, just return values
		return rews;
	}
	
	private MCRewards buildMCRewardStructure(Model model, RewardStruct rewStr) throws PrismException
	{
		//MCRewards modelRewards = null;
		List<State> statesList;
		Expression guard;
		int i, j, n, numStates;
		
		switch (model.getModelType()) {
		case CTMC:
		case DTMC:
			if (rewStr.getNumTransItems() > 0) {
				throw new PrismException("Explicit engine does not yet handle transition rewards");
			}
			// Special case: constant rewards
			if (rewStr.getNumStateItems() == 1 && Expression.isTrue(rewStr.getStates(0)) && rewStr.getReward(0).isConstant()) {
				return new MCRewardsStateConstant(rewStr.getReward(0).evaluateDouble());
			}
			// Normal: state rewards
			else {
				MCRewardsStateArray rewSA = new MCRewardsStateArray(model.getNumStates());
				numStates = model.getNumStates();
				statesList = model.getStatesList();
				n = rewStr.getNumItems();
				for (i = 0; i < n; i++) {
					guard = rewStr.getStates(i);
					for (j = 0; j < numStates; j++) {
						if (guard.evaluateBoolean(statesList.get(j))) {
							rewSA.setStateReward(j, rewStr.getReward(i).evaluateDouble(statesList.get(j)));
						}
					}
				}
				return rewSA;
			}
			//break;
		default:
			throw new PrismException("Cannot build rewards for " + model.getModelType() + "s");
		}
	}
}
