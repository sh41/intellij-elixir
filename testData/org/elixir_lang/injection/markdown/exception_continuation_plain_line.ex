defmodule ExceptionContinuationPlainLine do
  @moduledoc """
  A paragraph before the code block.

      ** (ArgumentError) argument error
      ...> :erlang.length(1)
      more error output

  A paragraph after the code block.
  """

  def example, do: :ok
end
